package com.xingheyuzhuan.shiguangschedule.ui.settings.contribution

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.model.ContributionList
import com.xingheyuzhuan.shiguangschedule.data.repository.ContributionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * UI 状态的密封类
 */
sealed interface ContributionUiState {
    object Loading : ContributionUiState
    data class Success(val data: ContributionList) : ContributionUiState
    data class Error(val message: String) : ContributionUiState
}

@HiltViewModel
class ContributionViewModel @Inject constructor(
    private val application: Application
) : ViewModel() {

    private val _uiState = MutableStateFlow<ContributionUiState>(ContributionUiState.Loading)
    val uiState: StateFlow<ContributionUiState> = _uiState.asStateFlow()

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex = _selectedTabIndex.asStateFlow()

    init {
        loadContributions()
    }

    fun loadContributions() {
        viewModelScope.launch {
            _uiState.value = ContributionUiState.Loading
            try {
                // 直接使用注入的 application 成员变量
                val data = ContributionRepository.getContributions(application)
                _uiState.value = ContributionUiState.Success(data)
            } catch (e: IOException) {
                _uiState.value = ContributionUiState.Error("数据加载失败: ${e.localizedMessage}")
            }
        }
    }

    fun selectTab(index: Int) {
        _selectedTabIndex.value = index
    }
}