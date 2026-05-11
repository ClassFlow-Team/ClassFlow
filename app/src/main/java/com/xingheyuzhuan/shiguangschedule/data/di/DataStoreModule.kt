package com.xingheyuzhuan.shiguangschedule.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.data.repository.scheduleGridStyleDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

// 定义 AppSettings Preferences DataStore 委托
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
// 定义 SchoolHistory Preferences DataStore 委托
private val Context.schoolHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "school_history")

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object DataStoreModule {

    /**
     * 提供课表网格样式 DataStore (Proto 模式)
     */
    @Provides
    @Singleton
    fun provideScheduleStyleDataStore(@ApplicationContext context: Context): DataStore<ScheduleGridStyleProto> {
        return context.scheduleGridStyleDataStore
    }

    /**
     * 提供学校选择历史 DataStore
     */
    @Provides
    @Singleton
    @Named("SchoolHistory")
    fun provideSchoolHistoryDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.schoolHistoryDataStore
    }

    /**
     * 提供全局设置 DataStore，并集成从 Room 到 DataStore 的单次自动迁移逻辑。
     * 原 Room 迁移逻辑已随版本 5 的物理删表操作一并移除。
     */
    @Provides
    @Singleton
    @Named("AppSettings")
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.appSettingsDataStore
}