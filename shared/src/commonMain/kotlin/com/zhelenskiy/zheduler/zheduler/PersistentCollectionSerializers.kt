package com.zhelenskiy.zheduler.zheduler

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Custom serializer for PersistentSet that delegates to SetSerializer.
 * Serializes as a regular set and deserializes into a PersistentSet.
 */
@OptIn(ExperimentalSerializationApi::class)
class PersistentSetSerializer<T>(elementSerializer: KSerializer<T>) : KSerializer<PersistentSet<T>> {
    private val delegateSerializer = SetSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = SerialDescriptor("PersistentSet", delegateSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: PersistentSet<T>) {
        delegateSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PersistentSet<T> {
        return delegateSerializer.deserialize(decoder).toPersistentSet()
    }
}

/**
 * Custom serializer for PersistentList that delegates to ListSerializer.
 * Serializes as a regular list and deserializes into a PersistentList.
 */
@OptIn(ExperimentalSerializationApi::class)
class PersistentListSerializer<T>(elementSerializer: KSerializer<T>) : KSerializer<PersistentList<T>> {
    private val delegateSerializer = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = SerialDescriptor("PersistentList", delegateSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: PersistentList<T>) {
        delegateSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PersistentList<T> {
        return delegateSerializer.deserialize(decoder).toPersistentList()
    }
}

/**
 * Maps elements of an Iterable to a PersistentList using the builder for efficiency.
 */
inline fun <T, R> Iterable<T>.mapToPersistentList(transform: (T) -> R): PersistentList<R> =
    buildPersistentList {
        for (element in this@mapToPersistentList) {
            add(transform(element))
        }
    }

/**
 * Maps elements of an Iterable to a PersistentSet using the builder for efficiency.
 */
inline fun <T, R> Iterable<T>.mapToPersistentSet(transform: (T) -> R): PersistentSet<R> =
    buildPersistentSet {
        for (element in this@mapToPersistentSet) {
            add(transform(element))
        }
    }

/**
 * Maps non-null results of transform to a PersistentList using the builder for efficiency.
 */
inline fun <T, R : Any> Iterable<T>.mapNotNullToPersistentList(transform: (T) -> R?): PersistentList<R> =
    buildPersistentList {
        for (element in this@mapNotNullToPersistentList) {
            transform(element)?.let { add(it) }
        }
    }

/**
 * Maps non-null results of transform to a PersistentSet using the builder for efficiency.
 */
inline fun <T, R : Any> Iterable<T>.mapNotNullToPersistentSet(transform: (T) -> R?): PersistentSet<R> =
    buildPersistentSet {
        for (element in this@mapNotNullToPersistentSet) {
            transform(element)?.let { add(it) }
        }
    }

/**
 * Filters elements to a PersistentSet using the builder for efficiency.
 */
inline fun <T> Iterable<T>.filterToPersistentSet(predicate: (T) -> Boolean): PersistentSet<T> =
    buildPersistentSet {
        for (element in this@filterToPersistentSet) {
            if (predicate(element)) add(element)
        }
    }

/**
 * FlatMaps elements to a PersistentSet using the builder for efficiency.
 */
inline fun <T, R> Iterable<T>.flatMapToPersistentSet(transform: (T) -> Iterable<R>): PersistentSet<R> =
    buildPersistentSet {
        for (element in this@flatMapToPersistentSet) {
            addAll(transform(element))
        }
    }

/**
 * Associates elements by a key selector to a PersistentMap using the builder for efficiency.
 */
inline fun <T, K> Iterable<T>.associateByToPersistentMap(keySelector: (T) -> K): PersistentMap<K, T> =
    buildPersistentMap {
        for (element in this@associateByToPersistentMap) {
            put(keySelector(element), element)
        }
    }

/**
 * Associates elements by a key selector and value transform to a PersistentMap using the builder for efficiency.
 */
inline fun <T, K, V> Iterable<T>.associateByToPersistentMap(keySelector: (T) -> K, valueTransform: (T) -> V): PersistentMap<K, V> =
    buildPersistentMap {
        for (element in this@associateByToPersistentMap) {
            put(keySelector(element), valueTransform(element))
        }
    }

/**
 * Builds a PersistentList using a builder block.
 */
inline fun <T> buildPersistentList(builderAction: MutableList<T>.() -> Unit): PersistentList<T> =
    persistentListOf<T>().builder().apply(builderAction).build()

/**
 * Builds a PersistentSet using a builder block.
 */
inline fun <T> buildPersistentSet(builderAction: MutableSet<T>.() -> Unit): PersistentSet<T> =
    persistentSetOf<T>().builder().apply(builderAction).build()

/**
 * Builds a PersistentMap using a builder block.
 */
inline fun <K, V> buildPersistentMap(builderAction: MutableMap<K, V>.() -> Unit): PersistentMap<K, V> =
    persistentMapOf<K, V>().builder().apply(builderAction).build()
