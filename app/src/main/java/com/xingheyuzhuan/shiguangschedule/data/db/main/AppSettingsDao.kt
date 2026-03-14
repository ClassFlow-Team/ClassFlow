package com.xingheyuzhuan.shiguangschedule.data.db.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room 数据访问对象 (DAO)，用于操作应用设置 (AppSettings) 数据表。
 * 此表仅包含一条记录。
 */
@Deprecated(
    message = "应用设置已迁移至 DataStore。读取请使用 AppSettingsRepository.appSettingsFlow，写入请使用 repository 对应的 update 方法。",
    level = DeprecationLevel.WARNING
)
@Dao
interface AppSettingsDao {

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getAppSettings(): Flow<AppSettings?>

    /**
     * 标记为 ERROR 可以强制让你在搬家期间不再写出错误代码
     */
    @Deprecated(
        message = "禁止再向 Room 写入设置数据，请使用 AppSettingsRepository。",
        level = DeprecationLevel.ERROR
    )
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(appSettings: AppSettings)
}