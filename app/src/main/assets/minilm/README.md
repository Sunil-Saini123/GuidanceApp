# MiniLM intent-classification model files

This folder must contain, before you build/run the app:

- `model_qint8_arm64.onnx` — the quantized ARM64 ONNX export of
  `sentence-transformers/all-MiniLM-L6-v2`
- `vocab.txt` — the matching bert-base-uncased WordPiece vocabulary

Neither file is checked in here (they're binary model assets, not source).
Download them once and drop them directly in this folder:

```
app/src/main/assets/minilm/
├── model_qint8_arm64.onnx
└── vocab.txt
```

Source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
(the `onnx/` subfolder of that repo has the quantized ARM64 export;
`vocab.txt` is at the repo root).

If `model_qint8_arm64.onnx` is missing or fails to load, the app does not
crash — `IntentClassifier` logs the failure once and `classify()` returns
`UNKNOWN_INTENT` forever after, so every query just falls through to the
existing `PathDatabase` keyword lookup.
