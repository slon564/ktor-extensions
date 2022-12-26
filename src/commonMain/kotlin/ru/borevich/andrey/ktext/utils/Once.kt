package ru.borevich.andrey.ktext.utils

import kotlin.reflect.KProperty

class Once<T> {
    var field: T? = null

    operator fun getValue(any: Any, kProperty1: KProperty<T?>): T? {
        return field
    }

    operator fun setValue(any: Any, kProperty1: KProperty<T?>, value: T) {
        if (field == null) field = value else throw TwiceException()
    }

    class TwiceException : IllegalStateException("Value can be written only once")
}

fun <T> once() = Once<T>()