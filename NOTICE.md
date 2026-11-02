# Karaza — License notice

Karaza is a modified version of **Yakuyomi** ([joyeli/Yakuyomi](https://github.com/joyeli/Yakuyomi), GPL-3.0), which is itself a fork of
**[Mihon](https://github.com/mihonapp/mihon)** (Apache-2.0). Karaza changes include Arabic translation support (right-to-left
typesetting, Arabic font and prompts), translation diagnostics, library-wide translation, quota handling, English-OCR fixes
and a new name, icon and identity. Changes are recorded in the git history of this repository and of the engine submodule.

The translation engine is included as a git submodule (`yakuyomi-engine/`, GPL-3.0; its translation pipeline is ported
from [manga-image-translator](https://github.com/zyddnys/manga-image-translator)). The engine in turn includes the
[yakuyomi-nightread](https://github.com/joyeli/yakuyomi-nightread) night-reading library (GPL-3.0) as its own submodule.

- **Mihon's original code**: Apache-2.0 — its [`LICENSE`](LICENSE) and attribution are preserved.
- **Yakuyomi's and Karaza's code, the bundled engine and night-reading library, and the combined app**: **GPL-3.0**
  (Apache-2.0 is compatible with GPL-3.0, so the combined work is GPL-3.0).
- **Model weights** (GPL-3.0): from [manga-image-translator](https://github.com/zyddnys/manga-image-translator) — DBNet text
  detection, 48px CTC OCR and AOT-GAN inpainting, converted to NCNN by the Yakuyomi project. The app downloads them from
  the Yakuyomi engine releases and checks each file against a SHA-256 list pinned in the app; you can also add them yourself.
- **Character-segmentation weights for night reading** (not GPL-3.0; optional, off by default), converted to NCNN by the
  Yakuyomi project, **for research / non-commercial use only**:
  - **YOLO11-seg** (`manga_seg_s`): from Hugging Face [anonimkaq4/manga-page-element-segmentation](https://huggingface.co/anonimkaq4/manga-page-element-segmentation)
    (model card `license: other`; [Ultralytics](https://github.com/ultralytics/ultralytics) YOLO11 is AGPL-3.0). Trained on
    MangaSeg / Manga109-s annotations; the model card asks redistributors and commercial users to verify the MangaSeg,
    Manga109-s and Ultralytics licenses, to credit it as "Copyrighted by Minshan Xie", and to cite the MangaSeg paper (CVPR 2025).
  - **CartoonSegmentation** (`cartoonseg`, RTMDet-Ins): from Hugging Face [Jakaline/CartoonSegmentationOnnx](https://huggingface.co/Jakaline/CartoonSegmentationOnnx);
    the upstream [CartoonSegmentation/CartoonSegmentation](https://github.com/CartoonSegmentation/CartoonSegmentation) repository
    states no license; the model card of the original PyTorch weights ([dreMaz/AnimeInstanceSegmentation](https://huggingface.co/dreMaz/AnimeInstanceSegmentation))
    says MIT; training data includes Manga109.
- **Fonts**: [Tajawal](https://fonts.google.com/specimen/Tajawal) Bold is bundled for Arabic text, under the SIL Open Font
  License 1.1 (`app/src/main/assets/fonts/Tajawal-OFL.txt`). Other scripts use the system fonts.
- **llama.cpp** ([ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp), MIT) runs the optional on-device translation
  model; it is a git submodule in `llm/llama.cpp` with its license.
- **On-device translation model** (optional, downloaded on request, not bundled):
  [IndexTeam/Index-Translate-2B](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) (Apache-2.0).
- **FlexibleAdapter** ([arkon/FlexibleAdapter](https://github.com/arkon/FlexibleAdapter), Apache-2.0) is vendored in
  `flexible-adapter/` with its license.

When distributing, keep this notice together with Mihon's `LICENSE` (Apache-2.0), the engine's `LICENSE` (GPL-3.0), the
night-reading library's `LICENSE` (GPL-3.0, `yakuyomi-engine/yakuyomi-nightread/LICENSE`), the Tajawal OFL text and
FlexibleAdapter's license.
