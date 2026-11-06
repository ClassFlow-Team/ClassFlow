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

// 定义 AppSettings Preferences DataStore 委托（公开供 MyApplication 手动构造仓库时共享同一实例）
val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
// 定义通用的 api_config 文件委托
private val Context.apiConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "api_config")
// 吹风机记录 / 判型策略：独立文件，天然不参与 WebDAV 同步（只存本机）
private val Context.hairdryerHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "hairdryer_hub")
// 校园设备管理（饮水机 / 洗衣机 / 洗浴）使用记录：独立文件，天然不参与 WebDAV 同步（只存本机）
private val Context.campusDeviceHubDataStore: DataStore<Preferences> by preferencesDataStore(name = "campus_device_hub")
// 洗衣进度与提醒（进行中订单快照 / 提醒开关）：独立文件，退出登录时可以单独清掉
private val Context.washerOrderDataStore: DataStore<Preferences> by preferencesDataStore(name = "washer_order")

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
     * 提供全局设置 DataStore（主题/偏好等）
     */
    @Provides
    @Singleton
    @Named("AppSettings")
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.appSettingsDataStore
    }

    /**
     * 提供通用的 API 配置 DataStore 实例（WebDAV 等）
     */
    @Provides
    @Singleton
    @Named("ApiConfig")
    fun provideApiConfigDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.apiConfigDataStore
    }

    /**
     * 吹风机页面的本地存储（使用记录 + 判型策略 + 桌面快捷方式图标）。
     *
     * 刻意放在独立文件：这些是「这台手机自己的使用习惯」，不属于要跟 WebDAV 同步的设置。
     */
    @Provides
    @Singleton
    @Named("HairdryerHub")
    fun provideHairdryerHubDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.hairdryerHubDataStore
    }

    /**
     * 校园设备管理页（饮水机 / 洗衣机 / 洗浴）的本地存储（使用记录 + 桌面快捷方式图标）。
     *
     * 刻意放在独立文件：这些是「这台手机自己的使用习惯」，不属于要跟 WebDAV 同步的设置。
     */
    @Provides
    @Singleton
    @Named("CampusDeviceHub")
    fun provideCampusDeviceHubDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.campusDeviceHubDataStore
    }

    /**
     * 洗衣进度 / 提醒的本地存储（进行中订单快照 + 两个开关）。
     *
     * 与设备使用记录分文件：订单是临时状态，用户退出登录或清缓存时应该能单独丢弃。
     */
    @Provides
    @Singleton
    @Named("WasherOrder")
    fun provideWasherOrderDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.washerOrderDataStore
    }
}
