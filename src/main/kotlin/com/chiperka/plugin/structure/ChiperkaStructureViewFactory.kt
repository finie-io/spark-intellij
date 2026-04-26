package com.chiperka.plugin.structure

import com.intellij.ide.structureView.*
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.*
import javax.swing.Icon
import com.intellij.icons.AllIcons

/**
 * Structure view for .chiperka files in the new spec format.
 *
 * Each .chiperka file is a multi-document YAML where every document is one of:
 *   - kind: Service   → service template
 *   - kind: Endpoint  → capability declaration
 *   - kind: Test      → test scenario (one per file in the recommended layout)
 *
 * The structure view shows one root node per document, then drills into
 * spec sections (services, execution, assertions, etc.) for Test documents.
 */
class ChiperkaStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (!psiFile.name.endsWith(".chiperka")) return null
        val yamlFile = psiFile as? YAMLFile ?: return null

        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return ChiperkaStructureViewModel(yamlFile, editor)
            }
        }
    }
}

private class ChiperkaStructureViewModel(
    file: YAMLFile,
    editor: Editor?,
) : StructureViewModelBase(file, editor, ChiperkaFileElement(file)), StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = false
}

private class ChiperkaFileElement(private val file: YAMLFile) : PsiTreeElementBase<YAMLFile>(file) {

    override fun getPresentableText(): String = file.name
    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.TestGroup

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        return file.documents.mapNotNull { doc ->
            val mapping = doc.topLevelValue as? YAMLMapping ?: return@mapNotNull null
            val kind = mapping.getKeyValueByKey("kind")?.valueText
            when (kind) {
                "Test" -> ChiperkaTestElement(mapping)
                "Service" -> ChiperkaServiceElement(mapping)
                "Endpoint" -> ChiperkaEndpointElement(mapping)
                else -> null
            }
        }
    }
}

private fun metadataName(m: YAMLMapping): String? {
    val metadata = m.getKeyValueByKey("metadata")?.value as? YAMLMapping ?: return null
    return metadata.getKeyValueByKey("name")?.valueText
}

private fun spec(m: YAMLMapping): YAMLMapping? =
    m.getKeyValueByKey("spec")?.value as? YAMLMapping

private class ChiperkaTestElement(private val mapping: YAMLMapping) : PsiTreeElementBase<YAMLMapping>(mapping) {

    override fun getPresentableText(): String = metadataName(mapping) ?: "Test"

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Test

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val s = spec(mapping) ?: return emptyList()
        val children = mutableListOf<StructureViewTreeElement>()

        s.getKeyValueByKey("endpoint")?.let { kv ->
            children.add(ChiperkaLeafElement(mapping, "endpoint: ${kv.valueText}", AllIcons.Nodes.Plugin))
        }
        s.getKeyValueByKey("services")?.let { kv ->
            children.add(ChiperkaSectionElement(kv, "services", AllIcons.Nodes.Deploy))
        }
        s.getKeyValueByKey("setup")?.let { kv ->
            children.add(ChiperkaSectionElement(kv, "setup", AllIcons.Actions.Install))
        }
        s.getKeyValueByKey("execution")?.let { kv ->
            children.add(ChiperkaSectionElement(kv, "execution", AllIcons.Actions.Execute))
        }
        s.getKeyValueByKey("assertions")?.let { kv ->
            children.add(ChiperkaSectionElement(kv, "assertions", AllIcons.Nodes.EntryPoints))
        }
        s.getKeyValueByKey("teardown")?.let { kv ->
            children.add(ChiperkaSectionElement(kv, "teardown", AllIcons.Actions.Uninstall))
        }
        return children
    }
}

private class ChiperkaServiceElement(private val mapping: YAMLMapping) : PsiTreeElementBase<YAMLMapping>(mapping) {

    override fun getPresentableText(): String {
        val name = metadataName(mapping) ?: "Service"
        val image = spec(mapping)?.getKeyValueByKey("image")?.valueText
        return if (image != null) "$name ($image)" else name
    }

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Plugin
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}

private class ChiperkaEndpointElement(private val mapping: YAMLMapping) : PsiTreeElementBase<YAMLMapping>(mapping) {

    override fun getPresentableText(): String {
        val name = metadataName(mapping) ?: "Endpoint"
        val s = spec(mapping)
        val service = s?.getKeyValueByKey("service")?.valueText
        val http = s?.getKeyValueByKey("endpoint")?.value as? YAMLMapping
        if (http != null) {
            val method = http.getKeyValueByKey("method")?.valueText ?: ""
            val url = http.getKeyValueByKey("url")?.valueText ?: ""
            return "$name [$method $url]"
        }
        val cmd = (s?.getKeyValueByKey("command")?.value as? YAMLMapping)
            ?.getKeyValueByKey("cmd")?.valueText
        if (cmd != null) {
            return "$name [cli: $cmd]"
        }
        return if (service != null) "$name ($service)" else name
    }

    override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Method
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}

private class ChiperkaSectionElement(
    private val kv: YAMLKeyValue,
    private val label: String,
    private val sectionIcon: Icon,
) : PsiTreeElementBase<YAMLKeyValue>(kv) {

    override fun getPresentableText(): String = label
    override fun getIcon(open: Boolean): Icon = sectionIcon

    override fun getChildrenBase(): Collection<StructureViewTreeElement> {
        val value = kv.value ?: return emptyList()
        return when (label) {
            "services" -> servicesChildren(value)
            "setup", "teardown" -> setupChildren(value)
            "assertions" -> assertionChildren(value)
            "execution" -> executionChildren(value)
            else -> emptyList()
        }
    }

    private fun servicesChildren(value: YAMLValue): List<StructureViewTreeElement> {
        val seq = value as? YAMLSequence ?: return emptyList()
        return seq.items.mapNotNull { item ->
            val m = item.value as? YAMLMapping ?: return@mapNotNull null
            val ref = m.getKeyValueByKey("ref")?.valueText ?: "unknown"
            val name = m.getKeyValueByKey("name")?.valueText
            val text = if (name != null && name != ref) "$ref as $name" else ref
            ChiperkaLeafElement(m, text, AllIcons.Nodes.Plugin)
        }
    }

    private fun setupChildren(value: YAMLValue): List<StructureViewTreeElement> {
        val seq = value as? YAMLSequence ?: return emptyList()
        return seq.items.mapIndexedNotNull { i, item ->
            val m = item.value as? YAMLMapping ?: return@mapIndexedNotNull null
            val httpKv = m.getKeyValueByKey("http")
            val cliKv = m.getKeyValueByKey("cli")
            val text = when {
                httpKv != null -> {
                    val httpMapping = httpKv.value as? YAMLMapping
                    val req = httpMapping?.getKeyValueByKey("request")?.value as? YAMLMapping
                    val method = req?.getKeyValueByKey("method")?.valueText ?: ""
                    val url = req?.getKeyValueByKey("url")?.valueText ?: ""
                    "http: $method $url"
                }
                cliKv != null -> {
                    val cliMapping = cliKv.value as? YAMLMapping
                    val cmd = cliMapping?.getKeyValueByKey("command")?.valueText ?: ""
                    "cli: $cmd"
                }
                else -> "step ${i + 1}"
            }
            ChiperkaLeafElement(m, text, AllIcons.Nodes.RunnableMark)
        }
    }

    private fun assertionChildren(value: YAMLValue): List<StructureViewTreeElement> {
        val seq = value as? YAMLSequence ?: return emptyList()
        return seq.items.mapNotNull { item ->
            val m = item.value as? YAMLMapping ?: return@mapNotNull null
            val type = m.keyValues.firstOrNull()?.keyText ?: "assertion"
            ChiperkaLeafElement(m, type, AllIcons.Nodes.EntryPoints)
        }
    }

    private fun executionChildren(value: YAMLValue): List<StructureViewTreeElement> {
        val m = value as? YAMLMapping ?: return emptyList()
        val executor = m.getKeyValueByKey("executor")?.valueText ?: "http"
        val text = when (executor) {
            "http" -> {
                val req = m.getKeyValueByKey("request")?.value as? YAMLMapping
                val method = req?.getKeyValueByKey("method")?.valueText ?: ""
                val url = req?.getKeyValueByKey("url")?.valueText ?: ""
                val target = m.getKeyValueByKey("target")?.valueText ?: ""
                "$method $target$url"
            }
            "cli" -> {
                val cli = m.getKeyValueByKey("cli")?.value as? YAMLMapping
                val cmd = cli?.getKeyValueByKey("command")?.valueText ?: ""
                val svc = cli?.getKeyValueByKey("service")?.valueText ?: ""
                "$svc: $cmd"
            }
            else -> executor
        }
        return listOf(ChiperkaLeafElement(m, text, AllIcons.Nodes.RunnableMark))
    }
}

private class ChiperkaLeafElement(
    private val psi: YAMLMapping,
    private val text: String,
    private val leafIcon: Icon,
) : PsiTreeElementBase<YAMLMapping>(psi) {

    override fun getPresentableText(): String = text
    override fun getIcon(open: Boolean): Icon = leafIcon
    override fun getChildrenBase(): Collection<StructureViewTreeElement> = emptyList()
}
