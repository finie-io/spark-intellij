package com.chiperka.plugin.lineMarker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.chiperka.plugin.run.ChiperkaRunUtil
import org.jetbrains.yaml.psi.*

/**
 * Adds gutter "Run" buttons to .chiperka files.
 *
 * In the new spec format every .chiperka file is a multi-document YAML where
 * each document declares one resource of `kind: Service | Endpoint | Test`.
 * Tests look like:
 *
 *   kind: Test
 *   metadata:
 *     name: success-returns-200
 *   spec:
 *     ...
 *
 * For each `kind: Test` document, the marker is attached to the
 * `metadata.name` key. Service and Endpoint documents are skipped — there's
 * nothing to run for those.
 */
class ChiperkaTestLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val file = element.containingFile ?: return null
        if (!file.name.endsWith(".chiperka")) return null
        val yamlFile = file as? YAMLFile ?: return null

        // Only attach to the `name:` key inside a top-level `metadata:` mapping
        // of a `kind: Test` document.
        val keyValue = element.parent as? YAMLKeyValue ?: return null
        if (keyValue.keyText != "name") return null
        if (element !== keyValue.key) return null

        val metadataMapping = keyValue.parent as? YAMLMapping ?: return null
        val metadataKv = metadataMapping.parent as? YAMLKeyValue ?: return null
        if (metadataKv.keyText != "metadata") return null

        val docMapping = metadataKv.parent as? YAMLMapping ?: return null
        val doc = docMapping.parent as? YAMLDocument ?: return null
        if (doc.parent !== yamlFile) return null

        val kind = docMapping.getKeyValueByKey("kind")?.valueText ?: return null
        if (kind != "Test") return null

        val testName = keyValue.valueText
        val filePath = file.virtualFile?.path ?: return null
        return createTestMarker(element, filePath, testName)
    }

    private fun createTestMarker(
        element: PsiElement,
        filePath: String,
        testName: String,
    ): LineMarkerInfo<PsiElement> {
        val label = "Run '$testName'"
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            { label },
            { _, psiElement -> runChiperka(psiElement, filePath, testName) },
            GutterIconRenderer.Alignment.CENTER,
            { label },
        )
    }

    private fun runChiperka(element: PsiElement, filePath: String, testName: String) {
        val project = element.project
        val configName = "Chiperka: $testName"
        val settings = ChiperkaRunUtil.findOrCreateConfig(project, filePath, testName, configName)
        ChiperkaRunUtil.showDialogAndRun(project, settings)
    }
}
