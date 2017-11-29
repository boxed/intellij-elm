package org.elm.lang.core.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import org.elm.lang.core.resolve.reference.ElmReference


interface ElmPsiElement : PsiElement {
    /**
     * Get the file containing this element as an [ElmFile]
     */
    val elmFile: ElmFile
}


abstract class ElmPsiElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), ElmPsiElement {

    companion object {
        private val EMPTY_REFERENCE_ARRAY = emptyArray<ElmReference>()
    }

    override val elmFile: ElmFile
        get() = containingFile as ElmFile

    // Make the type-system happy by using our reference interface instead of PsiReference
    override fun getReferences(): Array<ElmReference> {
        val ref = getReference() as? ElmReference ?: return EMPTY_REFERENCE_ARRAY
        return arrayOf(ref)
    }
}