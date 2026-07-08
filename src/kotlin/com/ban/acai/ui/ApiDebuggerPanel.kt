package com.ban.acai.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout
import javax.swing.JToolBar

/**
 * API调试面板（原始Kotlin版本）
 * 提供接口调试UI - URL输入、参数编辑、响应展示
 */
class ApiDebuggerPanel(private val project: Project) : JBPanel<ApiDebuggerPanel>(BorderLayout()) {
    private var currentApi: Any? = null

    init {
        setupUI()
    }

    private fun setupUI() {
        add(createToolbar(), BorderLayout.NORTH)
        // 原始Kotlin实现 - 包含参数Tab、请求体编辑器、响应展示等
    }

    private fun createToolbar(): JToolBar {
        val toolbar = JToolBar()
        toolbar.isFloatable = false
        return toolbar
    }

    fun loadApi(api: Any) {
        currentApi = api
        // 加载API信息到表单
    }
}
