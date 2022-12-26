package ru.borevich.andrey.ktext

c
import io.ktor.application.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.charset.StandardCharsets
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.*
import kotlin.reflect.jvm.jvmErasure


fun KTypeProjection.serializer() = type?.serializer() ?: error("No serializer for a star projection type")

@OptIn(InternalSerializationApi::class)
fun KType.serializer() : KSerializer<Any> {
    val arguments = this.arguments

    return when {
        arguments.isEmpty() -> this.jvmErasure.serializer()
        this.jvmErasure.isSubclassOf(List::class) -> {
            ListSerializer(arguments.single().serializer())
        }
        this.jvmErasure.isSubclassOf(Set::class) -> {
            SetSerializer(arguments.single().serializer())
        }
        this.jvmErasure.isSubclassOf(Map::class) -> {
            val (keyType, valueType) = arguments
            MapSerializer(keyType.serializer(), valueType.serializer())
        }
        else -> error("Not supported = '${this}'")
    } as KSerializer<Any>
}



@Suppress("UNCHECKED_CAST")
suspend fun queryBody(function: KFunction<*>, call: ApplicationCall, args: MutableList<Any?>) {
    val result = function.callSuspend(*args.toTypedArray())!!

    val serializer = function.returnType.serializer()

    val serializedResult = Json.encodeToString(serializer, result)
    call.respond(serializedResult)
}


val json = Json { isLenient = true }

@OptIn(InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
inline fun <reified C : ServerController> Route.rpc(tokenGenerator: TokenGenerator? = null) {
    val controllerKClass = C::class
    val createControllerInstance = { call: ApplicationCall ->
        controllerKClass.createInstance().also { instance ->
            instance.call = call
            tokenGenerator?.let { instance.tokenGenerator = it }
        }
    }

    controllerKClass.declaredFunctions.map { function ->
        if (function.name.startsWith("get")) {
            get(function.name) {
                val controller = createControllerInstance(call)
                val args = mutableListOf<Any?>(controller)
                function.valueParameters.mapTo(args) { param ->
                    call.request.queryParameters.getOrFail(param.name.toString())
                        .takeIf { it != null.toString() }
                        ?.let { strValue ->
                            json.decodeFromString(
                                param.type.jvmErasure.serializer(),
                                strValue
                            )
                        }
                }
                queryBody(function, call, args)
            }
        } else {
            post(function.name) {
                val controller = createControllerInstance(call)
                val text = String(call.receiveChannel().toByteArray(), StandardCharsets.UTF_8)
                val queryParameters = json.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    text
                )
                val args = mutableListOf<Any?>(controller)
                function.valueParameters.mapTo(args) { param ->
                    json.decodeFromString(
                        param.type.jvmErasure.serializer(),
                        queryParameters[param.name!!] ?: error("param is missing")
                    )
                }
                queryBody(function, call, args)
            }
        }
    }
}