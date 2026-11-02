package eu.kanade.tachiyomi.data.translation.local

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

class LocalTranslatorPromptTest {

    @Test
    fun `instruction is the one tested on the phone`() {
        LocalTranslator.instruction("Arabic") shouldBe
            "请将以下文本翻译为阿拉伯语，直接输出翻译结果，不要进行任何解释。" +
            "每一行都要保留开头的编号（例如 <|1|>），逐行翻译，不要合并或省略任何一行。"
    }

    @Test
    fun `template is ChatML with thinking turned off`() {
        val t = LocalTranslator.template("hi")
        t shouldStartWith "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n"
        t shouldEndWith "<think>\n\n</think>\n\n"
    }

    @Test
    fun `language names`() {
        LocalTranslator.chineseName("Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)") shouldBe "繁体中文"
        LocalTranslator.chineseName("English") shouldBe "英语"
        LocalTranslator.instruction("Klingon") shouldContain "Klingon"
    }

    @Test
    fun `pinned model file`() {
        LocalModel.SHA256.length shouldBe 64
        LocalModel.URL shouldStartWith "https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/resolve/main/"
    }
}
