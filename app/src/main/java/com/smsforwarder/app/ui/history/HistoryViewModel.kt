package com.smsforwarder.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import com.smsforwarder.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val selectedFilter: TaskStatus? = null,
    val searchQuery: String = "",
    val tasks: List<DeliveryTask> = emptyList(),
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

class HistoryViewModel(
    private val queueRepository: DeliveryQueueRepository,
    private val smsRepository: SmsRepository
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow<TaskStatus?>(null)
    private val _searchQuery = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = combine(
        _selectedFilter,
        _searchQuery,
        queueRepository.getAllTasksFlow()
    ) { filter, query, allTasks ->
        val filteredByStatus = if (filter == null) {
            allTasks
        } else {
            allTasks.filter { it.status == filter }
        }
        val trimmedQuery = query.trim()
        val filtered = if (trimmedQuery.isBlank()) {
            filteredByStatus
        } else {
            filteredByStatus.filter { task ->
                task.destinationEndpoint.contains(trimmedQuery, ignoreCase = true) ||
                task.destinationType.name.contains(trimmedQuery, ignoreCase = true) ||
                (task.errorMessage?.contains(trimmedQuery, ignoreCase = true) == true) ||
                task.id.contains(trimmedQuery, ignoreCase = true)
            }
        }
        val success = allTasks.count { it.status == TaskStatus.SUCCESS }
        val failed = allTasks.count { it.status == TaskStatus.FAILED_RETRYABLE || it.status == TaskStatus.FAILED_EXHAUSTED }

        HistoryUiState(
            selectedFilter = filter,
            searchQuery = query,
            tasks = filtered,
            totalCount = allTasks.size,
            successCount = success,
            failureCount = failed
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState()
    )

    fun setFilter(status: TaskStatus?) {
        _selectedFilter.value = status
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    suspend fun getSmsForTask(smsRecordId: String): SmsMessage? {
        return smsRepository.getSmsById(smsRecordId)
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            queueRepository.retryTaskManually(taskId)
            com.smsforwarder.app.engine.queue.QueueDrainer.drainQueue(com.smsforwarder.app.SmsForwarderApp.instance)
        }
    }

    fun retryAllFailed() {
        flushOrRetryAll()
    }

    fun flushOrRetryAll() {
        viewModelScope.launch {
            com.smsforwarder.app.engine.queue.QueueManager.retryAllFailedTasks(com.smsforwarder.app.SmsForwarderApp.instance)
            com.smsforwarder.app.engine.queue.QueueDrainer.drainQueue(com.smsforwarder.app.SmsForwarderApp.instance)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            queueRepository.deleteAllTasks()
        }
    }
}
