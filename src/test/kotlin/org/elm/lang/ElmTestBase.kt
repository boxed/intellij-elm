/*
The MIT License (MIT)

Derived from intellij-rust
Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
Copyright (c) 2016 JetBrains

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package org.elm.lang

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase
import junit.framework.AssertionFailedError
import org.elm.FileTree
import org.elm.TestProject
import org.elm.fileTreeFromText
import org.elm.lang.core.psi.parentOfType
import org.intellij.lang.annotations.Language


/**
 * Base class for basically all Elm tests *except* lexing and parsing.
 *
 * Features:
 * - defines a project descriptor suitable for IntelliJ's integration test system
 * - test util functions checkBy{File,Directory,Text} that run and compare against expected output
 * - test util functions findElement...InEditor that identify a mark in the source input
 *   and make it easy to check facts about the PsiElement at the indicated position
 *
 * We don't use this base class for lexing and parsing because IntelliJ already
 * provides [LexerTestCase] and [ParsingTestCase] for that purpose.
 */
abstract class ElmTestBase : LightPlatformCodeInsightFixtureTestCase(), ElmTestCase {

    override fun getProjectDescriptor(): LightProjectDescriptor = ElmProjectDescriptor()

    override fun isWriteActionRequired(): Boolean = false

    open val dataPath: String = ""

    override fun getTestDataPath(): String = "${ElmTestCase.testResourcesPath}/$dataPath"

    protected val fileName: String
        get() = "$testName.elm"

    protected val testName: String
        get() = camelOrWordsToSnake(getTestName(true))

    protected fun checkByFile(ignoreTrailingWhitespace: Boolean = true, action: () -> Unit) {
        val (before, after) = (fileName to fileName.replace(".elm", "_after.elm"))
        myFixture.configureByFile(before)
        action()
        myFixture.checkResultByFile(after, ignoreTrailingWhitespace)
    }

    protected fun checkByDirectory(action: () -> Unit) {
        val (before, after) = ("$testName/before" to "$testName/after")

        val targetPath = ""
        val beforeDir = myFixture.copyDirectoryToProject(before, targetPath)

        action()

        val afterDir = getVirtualFileByName("$testDataPath/$after")
        PlatformTestUtil.assertDirectoriesEqual(afterDir, beforeDir)
    }

    protected fun checkByDirectory(@Language("Elm") before: String, @Language("Elm") after: String, action: () -> Unit) {
        fileTreeFromText(before).create()
        action()
        FileDocumentManager.getInstance().saveAllDocuments()
        fileTreeFromText(after).assertEquals(myFixture.findFileInTempDir("."))
    }

    protected fun checkByText(
            @Language("Elm") before: String,
            @Language("Elm") after: String,
            action: () -> Unit
    ) {
        InlineFile(before)
        action()
        myFixture.checkResult(replaceCaretMarker(after))
    }

    protected fun openFileInEditor(path: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
    }

    protected fun getVirtualFileByName(path: String): VirtualFile? =
            LocalFileSystem.getInstance().findFileByPath(path)

    protected inline fun <reified X : Throwable> expect(f: () -> Unit) {
        try {
            f()
        } catch (e: Throwable) {
            if (e is X)
                return
            throw e
        }
        fail("No ${X::class.java} was thrown during the test")
    }

    inner class InlineFile(private @Language("Elm") val code: String, val name: String = "main.elm") {
        private val hasCaretMarker = "{-caret-}" in code

        init {
            myFixture.configureByText(name, replaceCaretMarker(code))
        }

        fun withCaret() {
            check(hasCaretMarker) {
                "Please, add `{-caret-}` marker to\n$code"
            }
        }
    }

    protected inline fun <reified T : PsiElement> findElementInEditor(marker: String = "^"): T {
        val (element, data) = findElementWithDataAndOffsetInEditor<T>(marker)
        check(data.isEmpty()) { "Did not expect marker data" }
        return element
    }

    protected inline fun <reified T : PsiElement> findElementAndDataInEditor(marker: String = "^"): Pair<T, String> {
        val (element, data) = findElementWithDataAndOffsetInEditor<T>(marker)
        return element to data
    }

    protected inline fun <reified T : PsiElement> findElementWithDataAndOffsetInEditor(marker: String = "^"): Triple<T, String, Int> {
        val elmLineComment = "--"
        val caretMarker = "$elmLineComment$marker"
        val (elementAtMarker, data, offset) = run {
            val text = myFixture.file.text
            val markerOffset = text.indexOf(caretMarker)
            check(markerOffset != -1) { "No `$marker` marker:\n$text" }
            check(text.indexOf(caretMarker, startIndex = markerOffset + 1) == -1) {
                "More than one `$marker` marker:\n$text"
            }

            val data = text.drop(markerOffset).removePrefix(caretMarker).takeWhile { !it.isWhitespace() }.trim()
            val markerPosition = myFixture.editor.offsetToLogicalPosition(markerOffset + caretMarker.length - 1)
            val previousLine = LogicalPosition(markerPosition.line - 1, markerPosition.column)
            val elementOffset = myFixture.editor.logicalPositionToOffset(previousLine)
            Triple(myFixture.file.findElementAt(elementOffset)!!, data, elementOffset)
        }
        val element = elementAtMarker.parentOfType<T>(strict = false)
                ?: error("No ${T::class.java.simpleName} at ${elementAtMarker.text}")
        return Triple(element, data, offset)
    }

    protected fun replaceCaretMarker(text: String) = text.replace("{-caret-}", "<caret>")

    protected fun applyQuickFix(name: String) {
        val action = myFixture.findSingleIntention(name)
        myFixture.launchAction(action)
    }

    protected fun checkAstNotLoaded(fileFilter: VirtualFileFilter) {
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(fileFilter, testRootDisposable)
    }

    class ElmProjectDescriptor : LightProjectDescriptor()

    companion object {
        // XXX: hides `Assert.fail`
        fun fail(message: String): Nothing {
            throw AssertionFailedError(message)
        }

        @JvmStatic
        fun camelOrWordsToSnake(name: String): String {
            if (' ' in name) return name.replace(" ", "_")

            return name.split("(?=[A-Z])".toRegex())
                    .map(String::toLowerCase)
                    .joinToString("_")
        }

        @JvmStatic fun getResourceAsString(path: String): String? {
            val stream = ElmTestBase::class.java.classLoader.getResourceAsStream(path)
                    ?: return null

            return StreamUtil.readText(stream, Charsets.UTF_8)
        }
    }

    protected fun FileTree.create(): TestProject =
            create(myFixture.project, myFixture.findFileInTempDir("."))

    protected fun FileTree.createAndOpenFileWithCaretMarker(): TestProject {
        val testProject = create()
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)
        return testProject
    }
}