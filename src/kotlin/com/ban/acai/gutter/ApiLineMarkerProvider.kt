package com.ban.acai.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.util.IconUtil
import javax.swing.Icon

/**
 * API接口Gutter图标提供器（原始Kotlin版本）
 * 在Controller方法旁显示API调试按钮
 */
class ApiLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiMethod) return null
        // 原始Kotlin实现 - 检测Spring MVC/JAX-RS注解方法
        // 在Java版本中已完整实现
        return null
    }

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        // 原始Kotlin实现
    }
}
