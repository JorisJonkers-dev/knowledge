package com.jorisjonkers.personalstack.knowledge.mcp

internal fun Any?.stringKeyMap(): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: error("expected map value")
    return raw.entries.associate { (key, value) ->
        check(key is String) { "expected string map key but got $key" }
        key to value
    }
}

internal fun Any?.stringKeyMapList(): List<Map<String, Any?>> {
    val raw = this as? List<*> ?: error("expected list value")
    return raw.map { it.stringKeyMap() }
}

internal fun Any?.stringList(): List<String> {
    val raw = this as? List<*> ?: error("expected string list")
    return raw.map { value ->
        value as? String ?: error("expected string list item but got $value")
    }
}
