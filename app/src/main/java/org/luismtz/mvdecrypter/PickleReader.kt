package org.luismtz.mvdecrypter

import java.io.ByteArrayOutputStream

/**
 * Intérprete mínimo del formato pickle de Python (protocolos 0-4).
 *
 * Suficiente para leer estructuras simples: dict, list, tuple, str, bytes,
 * int, bool, None — que es todo lo que aparece en el índice de un archivo
 * Ren'Py (.rpa). No ejecuta código arbitrario: GLOBAL/REDUCE/NEWOBJ solo se
 * reconocen para reconstruir contenedores tipo dict (ej. collections.defaultdict),
 * cualquier otra clase referenciada se ignora seguramente.
 */
class PickleReader(private val data: ByteArray) {

    private var pos = 0
    private val marks = ArrayDeque<Int>() // índices dentro de 'stackList' que marcan un MARK
    private val memo = HashMap<Int, Any?>()

    // Representamos la pila como una lista real para poder hacer slicing desde un MARK
    private val stackList = ArrayList<Any?>()

    private class GlobalRef(val module: String, val name: String)

    fun load(): Any? {
        while (true) {
            val opcode = readByte()
            when (opcode) {
                0x80 -> { readByte() } // PROTO + version byte
                0x95.toByte() -> { pos += 8 } // FRAME + 8 bytes length (ignorado)
                '('.code.toByte() -> marks.addLast(stackList.size) // MARK
                '.'.code.toByte() -> return stackList.removeLastOrNull() // STOP
                '}'.code.toByte() -> stackList.add(LinkedHashMap<Any?, Any?>()) // EMPTY_DICT
                ']'.code.toByte() -> stackList.add(ArrayList<Any?>()) // EMPTY_LIST
                ')'.code.toByte() -> stackList.add(emptyList<Any?>()) // EMPTY_TUPLE
                'N'.code.toByte() -> stackList.add(null) // NONE
                0x88.toByte() -> stackList.add(true) // NEWTRUE
                0x89.toByte() -> stackList.add(false) // NEWFALSE
                'K'.code.toByte() -> stackList.add((readByte().toInt() and 0xFF).toLong()) // BININT1
                'M'.code.toByte() -> stackList.add(readUInt16LE().toLong()) // BININT2
                'J'.code.toByte() -> stackList.add(readInt32LE().toLong()) // BININT
                0x8a.toByte() -> stackList.add(readLong1()) // LONG1
                0x8b.toByte() -> stackList.add(readLong4()) // LONG4
                'X'.code.toByte() -> stackList.add(readUnicode(readUInt32LE().toInt())) // BINUNICODE
                0x8c.toByte() -> stackList.add(readUnicode(readByte().toInt() and 0xFF)) // SHORT_BINUNICODE
                0x8d.toByte() -> stackList.add(readUnicode(readUInt32LE().toInt())) // BINUNICODE8 (usamos 32 bits, suficiente aquí)
                'C'.code.toByte() -> stackList.add(readBytes(readByte().toInt() and 0xFF)) // SHORT_BINBYTES
                'B'.code.toByte() -> stackList.add(readBytes(readUInt32LE().toInt())) // BINBYTES
                0x8e.toByte() -> stackList.add(readBytes(readUInt32LE().toInt())) // BINBYTES8
                'a'.code.toByte() -> { // APPEND
                    val v = stackList.removeAt(stackList.size - 1)
                    @Suppress("UNCHECKED_CAST")
                    (stackList.last() as ArrayList<Any?>).add(v)
                }
                'e'.code.toByte() -> { // APPENDS
                    val mark = marks.removeLast()
                    val items = ArrayList(stackList.subList(mark, stackList.size))
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                    @Suppress("UNCHECKED_CAST")
                    (stackList.last() as ArrayList<Any?>).addAll(items)
                }
                's'.code.toByte() -> { // SETITEM
                    val v = stackList.removeAt(stackList.size - 1)
                    val k = stackList.removeAt(stackList.size - 1)
                    @Suppress("UNCHECKED_CAST")
                    (stackList.last() as MutableMap<Any?, Any?>)[k] = v
                }
                'u'.code.toByte() -> { // SETITEMS
                    val mark = marks.removeLast()
                    val items = ArrayList(stackList.subList(mark, stackList.size))
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                    @Suppress("UNCHECKED_CAST")
                    val m = stackList.last() as MutableMap<Any?, Any?>
                    var i = 0
                    while (i < items.size) { m[items[i]] = items[i + 1]; i += 2 }
                }
                't'.code.toByte() -> { // TUPLE
                    val mark = marks.removeLast()
                    val items = ArrayList(stackList.subList(mark, stackList.size))
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                    stackList.add(items)
                }
                0x85.toByte() -> { // TUPLE1
                    val a = stackList.removeAt(stackList.size - 1)
                    stackList.add(listOf(a))
                }
                0x86.toByte() -> { // TUPLE2
                    val b = stackList.removeAt(stackList.size - 1)
                    val a = stackList.removeAt(stackList.size - 1)
                    stackList.add(listOf(a, b))
                }
                0x87.toByte() -> { // TUPLE3
                    val c = stackList.removeAt(stackList.size - 1)
                    val b = stackList.removeAt(stackList.size - 1)
                    val a = stackList.removeAt(stackList.size - 1)
                    stackList.add(listOf(a, b, c))
                }
                0x94.toByte() -> { memo[memo.size] = stackList.last() } // MEMOIZE
                'q'.code.toByte() -> { memo[readByte().toInt() and 0xFF] = stackList.last() } // BINPUT
                'r'.code.toByte() -> { memo[readUInt32LE().toInt()] = stackList.last() } // LONG_BINPUT
                'h'.code.toByte() -> { stackList.add(memo[readByte().toInt() and 0xFF]) } // BINGET
                'j'.code.toByte() -> { stackList.add(memo[readUInt32LE().toInt()]) } // LONG_BINGET
                'c'.code.toByte() -> { // GLOBAL: module\nname\n
                    val module = readLineAscii()
                    val name = readLineAscii()
                    stackList.add(GlobalRef(module, name))
                }
                0x93.toByte() -> { // STACK_GLOBAL
                    val name = stackList.removeAt(stackList.size - 1) as String
                    val module = stackList.removeAt(stackList.size - 1) as String
                    stackList.add(GlobalRef(module, name))
                }
                'R'.code.toByte() -> { // REDUCE: pop args, pop callable -> push instancia
                    val args = stackList.removeAt(stackList.size - 1)
                    val callable = stackList.removeAt(stackList.size - 1)
                    stackList.add(reduceToValue(callable))
                }
                0x81.toByte() -> { // NEWOBJ: pop args, pop cls -> push instancia
                    val args = stackList.removeAt(stackList.size - 1)
                    val cls = stackList.removeAt(stackList.size - 1)
                    stackList.add(reduceToValue(cls))
                }
                'b'.code.toByte() -> { // BUILD: pop state, ignorar (mantiene el objeto tal cual)
                    stackList.removeAt(stackList.size - 1)
                }
                '0'.code.toByte() -> stackList.removeAt(stackList.size - 1) // POP
                '1'.code.toByte() -> { // POP_MARK
                    val mark = marks.removeLast()
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                }
                '2'.code.toByte() -> stackList.add(stackList.last()) // DUP
                'd'.code.toByte() -> { // DICT (protocolo 0, poco común aquí)
                    val mark = marks.removeLast()
                    val items = ArrayList(stackList.subList(mark, stackList.size))
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                    val m = LinkedHashMap<Any?, Any?>()
                    var i = 0
                    while (i < items.size) { m[items[i]] = items[i + 1]; i += 2 }
                    stackList.add(m)
                }
                'l'.code.toByte() -> { // LIST
                    val mark = marks.removeLast()
                    val items = ArrayList(stackList.subList(mark, stackList.size))
                    while (stackList.size > mark) stackList.removeAt(stackList.size - 1)
                    stackList.add(items)
                }
                else -> throw IllegalStateException("Opcode pickle no soportado: 0x${"%02x".format(opcode)} en posición $pos")
            }
        }
    }

    /** Convierte un GLOBAL/callable reducido a un valor utilizable; contenedores tipo dict -> mapa vacío. */
    private fun reduceToValue(callable: Any?): Any? {
        if (callable is GlobalRef) {
            return when {
                callable.name.contains("defaultdict", true) -> LinkedHashMap<Any?, Any?>()
                callable.name.contains("dict", true) -> LinkedHashMap<Any?, Any?>()
                callable.name.contains("OrderedDict", true) -> LinkedHashMap<Any?, Any?>()
                else -> LinkedHashMap<Any?, Any?>() // best-effort: tratamos cualquier clase desconocida como mapa
            }
        }
        return LinkedHashMap<Any?, Any?>()
    }

    private fun readByte(): Byte = data[pos++]

    private fun readUInt16LE(): Int {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        pos += 2
        return b0 or (b1 shl 8)
    }

    private fun readInt32LE(): Int {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF
        val b3 = data[pos + 3].toInt() and 0xFF
        pos += 4
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readUInt32LE(): Long = readInt32LE().toLong() and 0xFFFFFFFFL

    private fun readLong1(): Long {
        val n = readByte().toInt() and 0xFF
        var result = 0L
        for (i in 0 until n) {
            result = result or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += n
        // Signo (complemento a dos) si el bit más alto del último byte está activo
        if (n > 0 && (data[pos - 1].toInt() and 0x80) != 0) {
            result -= (1L shl (8 * n))
        }
        return result
    }

    private fun readLong4(): Long {
        val n = readUInt32LE().toInt()
        var result = 0L
        for (i in 0 until n) {
            result = result or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += n
        return result
    }

    private fun readUnicode(len: Int): String {
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    private fun readBytes(len: Int): ByteArray {
        val b = data.copyOfRange(pos, pos + len)
        pos += len
        return b
    }

    private fun readLineAscii(): String {
        val start = pos
        while (data[pos] != '\n'.code.toByte()) pos++
        val s = String(data, start, pos - start, Charsets.US_ASCII)
        pos++ // saltar el \n
        return s
    }
}
