package io.codearte.accurest.util
import groovy.json.JsonSlurper
import io.codearte.accurest.dsl.internal.DslProperty
import io.codearte.accurest.dsl.internal.ExecutionProperty

import java.util.regex.Pattern
/**
 * @author Marcin Grzejszczak
 */
class JsonPathJsonConverter {

	private static final Boolean SERVER_SIDE = false
	private static final Boolean CLIENT_SIDE = true

	public static final String ROOT_JSON_PATH_ELEMENT = '$'
	public static final String ALL_ELEMENTS = "[*]"

	public static JsonPaths transformToJsonPathWithTestsSideValues(def json) {
		return transformToJsonPathWithValues(json, SERVER_SIDE)
	}

	public static JsonPaths transformToJsonPathWithStubsSideValues(def json) {
		return transformToJsonPathWithValues(json, CLIENT_SIDE)
	}

	private static JsonPaths transformToJsonPathWithValues(def json, boolean clientSide) {
		JsonPaths pathsAndValues = [] as Set
		Object convertedJson = getClientOrServerSideValues(json, clientSide)
		traverseRecursivelyForKey(convertedJson, ROOT_JSON_PATH_ELEMENT, pathsAndValues) { boolean applyFiltering = false, String key, Object value ->
			if (value instanceof ExecutionProperty) {
				return
			}
			JsonPathEntry entry = getValueToInsert(applyFiltering, key, value)
			pathsAndValues.add(entry)
		}
		return pathsAndValues
	}

	private static Object getClientOrServerSideValues(json, boolean clientSide) {
		return JsonConverter.transformValues(json) {
			boolean dslProp = it instanceof DslProperty
			if (dslProp) {
				DslProperty dslProperty = ((DslProperty) it)
				return clientSide ?
						getClientOrServerSideValues(dslProperty.clientValue, clientSide) : getClientOrServerSideValues(dslProperty.serverValue, clientSide)
			}
			return it
		}
	}

	protected static def traverseRecursively(Class parentType, String key, def value, Closure closure) {
		if (value instanceof String && value) {
			try {
				def json = new JsonSlurper().parseText(value)
				if (json instanceof Map) {
					return convertWithKey(parentType, key, json, closure)
				}
			} catch (Exception ignore) {
				return closure(parentType == List, key, value)
			}
		} else if (isAnEntryWithNonCollectionLikeValue(value)) {
			return convertWithKey(List, key, value as Map, closure)
		} else if (isAnEntryWithoutNestedStructures(value)) {
			return convertWithKey(List, key, value as Map, closure)
		} else if (value instanceof Map) {
			return convertWithKey(Map, key, value as Map, closure)
		} else if (value instanceof List) {
			value.each { def element ->
				traverseRecursively(List, "$key[*]", element, closure)
			}
			return value
		}
		try {
			return closure(parentType == List, key, value)
		} catch (Exception ignore) {
			return value
		}
	}

	private static boolean isAnEntryWithNonCollectionLikeValue(def value) {
		if (!(value instanceof Map)) {
			return false
		}
		Map valueAsMap = ((Map) value)
		boolean mapHasOneEntry = valueAsMap.size() == 1
		if (!mapHasOneEntry) {
			return false
		}
		Object valueOfEntry = valueAsMap.entrySet().first().value
		return !(valueOfEntry instanceof Map || valueOfEntry instanceof List)
	}

	private static boolean isAnEntryWithoutNestedStructures(def value) {
		if (!(value instanceof Map)) {
			return false
		}
		Map valueAsMap = ((Map) value)
		return valueAsMap.entrySet().every { Map.Entry entry ->
			[String, Number].any { entry.value.getClass().isAssignableFrom(it) }
		}
	}

	private static Map convertWithKey(Class parentType, String parentKey, Map map, Closure closureToExecute) {
		return map.collectEntries {
			String entrykey, value ->
				[entrykey, traverseRecursively(parentType, "${parentKey}.${entrykey}", value, closureToExecute)]
		}
	}

	private static void traverseRecursivelyForKey(def json, String rootKey, JsonPaths pathsAndValues, Closure closure) {
		traverseRecursively(Map, rootKey, json, closure)
	}

	private static JsonPathEntry getValueToInsert(boolean applyFiltering, String key, Object value) {
		return applyFiltering ? convertToListElementFiltering(key, value) : JsonPathEntry.simple(key, value)
	}

	protected static JsonPathEntry convertToListElementFiltering(String key, Object value) {
		if (key.endsWith(ALL_ELEMENTS)) {
			int lastAllElements = key.lastIndexOf(ALL_ELEMENTS)
			String keyWithoutAllElements = key.substring(0, lastAllElements)
			return JsonPathEntry.simple("""$keyWithoutAllElements[?(@ ${compareWith(value)})]""".toString(), value)
		}
		return getKeyForTraversalOfListWithNonPrimitiveTypes(key, value)
	}

	private static JsonPathEntry getKeyForTraversalOfListWithNonPrimitiveTypes(String key, Object value) {
		int lastDot = key.lastIndexOf('.')
		String keyWithoutLastElement = key.substring(0, lastDot)
		String lastElement = key.substring(lastDot + 1).replaceAll(~/\[\*\]/, "")
		return new JsonPathEntry(
				"""$keyWithoutLastElement[?(@.$lastElement ${compareWith(value)})]""".toString(),
				lastElement,
				value
		)
	}

	protected static String compareWith(Object value) {
		if (value instanceof Pattern) {
			return """=~ /${(value as Pattern).pattern()}/"""
		}
		return """== '$value'"""
	}

}
