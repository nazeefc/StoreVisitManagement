package com.example.storevisitmanagement.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.storevisitmanagement.data.local.entity.StoreEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreDao {
    @Query("SELECT * FROM stores WHERE assignedEmployeeId = :employeeId ORDER BY name ASC")
    fun getStoresForEmployee(employeeId: String): Flow<List<StoreEntity>>

    @Query("SELECT * FROM stores WHERE assignedEmployeeId = :employeeId ORDER BY name ASC")
    suspend fun getStoresSync(employeeId: String): List<StoreEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(stores: List<StoreEntity>): List<Long>

    @Update
    suspend fun updateStores(stores: List<StoreEntity>)

    @Transaction
    suspend fun upsertStores(stores: List<StoreEntity>) {
        if (stores.isEmpty()) return
        val insertResults = insertIgnore(stores)
        val updateList = mutableListOf<StoreEntity>()
        for (i in insertResults.indices) {
            if (insertResults[i] == -1L) {
                updateList.add(stores[i])
            }
        }
        if (updateList.isNotEmpty()) {
            updateStores(updateList)
        }
    }

    @Query("SELECT * FROM stores WHERE id = :storeId LIMIT 1")
    suspend fun getStoreById(storeId: String): StoreEntity?
}
