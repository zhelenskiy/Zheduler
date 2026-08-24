package com.zhelenskiy.zheduler.zheduler.store

/** The contract, against the store the API tests use. */
class InMemorySyncStoreTest : SyncStoreContractTest() {
    override fun createStore(): SyncStore = InMemorySyncStore()
}
