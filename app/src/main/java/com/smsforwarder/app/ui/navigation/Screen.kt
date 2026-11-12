package com.smsforwarder.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Rules : Screen("rules", "Rules", Icons.Default.List)
    data object History : Screen("history", "History", Icons.Default.History)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object RuleEdit : Screen("rule_edit?ruleId={ruleId}", "Rule Editor") {
        fun createRoute(ruleId: String? = null): String {
            return if (ruleId != null) "rule_edit?ruleId=$ruleId" else "rule_edit"
        }
    }
}
