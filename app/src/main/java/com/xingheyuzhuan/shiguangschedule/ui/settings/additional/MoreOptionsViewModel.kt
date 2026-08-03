package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.AppSettingsModel
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 更多选项页 ViewModel（启动页面设置等 DataStore 偏好）
 */
@HiltViewModel
class MoreOptionsViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository
) : ViewModel() {

    /** 当前启动页面 */
    val startScreen: StateFlow<StartScreen> = appSettingsRepository.getAppSettings()
        .map { it.startScreen }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettingsModel().startScreen
        )

    /** 更新应用启动时的默认主页 */
    fun onStartScreenChanged(newScreen: StartScreen) {
        viewModelScope.launch {
            val currentSettings = appSettingsRepository.getAppSettingsOnce()
            appSettingsRepository.insertOrUpdateAppSettings(
                currentSettings.copy(startScreen = newScreen)
            )
        }
    }
}
