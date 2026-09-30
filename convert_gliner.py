#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Zoptymalizowany skrypt konwersji modelu GLiNER 2.5 (mDeBERTa-v3) do formatu LiteRT (.tflite).
Naprawiono obsługę niestandardowego typu architektury przy użyciu biblioteki gliner2.
"""

import os
import sys
import argparse

def main():
    parser = argparse.ArgumentParser(description="Konwersja GLiNER 2.5 do formatu LiteRT (.tflite).")
    parser.add_argument("--model_id", type=str, default="fastino/gliner2.5-multi-v1", help="Hugging Face Model ID")
    parser.add_argument("--max_tokens", type=int, default=128, help="Staly rozmiar max_tokens")
    parser.add_argument("--output_dir", type=str, default="./gliner_output", help="Katalog wyjsciowy")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    onnx_path = os.path.join(args.output_dir, "gliner_static.onnx")
    tf_saved_model_path = os.path.join(args.output_dir, "saved_model")
    tflite_path = os.path.join(args.output_dir, "gliner_2.5_fp16.tflite")
    tokenizer_dest = os.path.join(args.output_dir, "tokenizer.json")

    print(f"[KROK 1/5] Inicjalizacja środowiska i pobieranie bibliotek...")
    try:
        import torch
        from transformers import AutoTokenizer
        from gliner2 import AutoExtractor
    except ImportError:
        print("Blad: Brak wymaganych bibliotek w systemie.")
        sys.exit(1)

    print(f"[KROK 2/5] Pobieranie tokenizera i modelu {args.model_id} przez gliner2...")
    tokenizer = AutoTokenizer.from_pretrained(args.model_id)
    tokenizer.save_pretrained(args.output_dir)

    # POPRAWKA AUDYTU: Używamy dedykowanej klasy AutoExtractor z gliner2, 
    # która bezbłędnie kompiluje niestandardowy typ "extractor"
    gliner_model = AutoExtractor.from_pretrained(args.model_id)
    pytorch_model = gliner_model.model
    pytorch_model.eval()

    print(f"[KROK 3/5] Eksport do formatu ONNX ze stalym rozmiarem (max_tokens={args.max_tokens})...")
    dummy_input_ids = torch.ones((1, args.max_tokens), dtype=torch.int32)
    dummy_attention_mask = torch.ones((1, args.max_tokens), dtype=torch.int32)
    dummy_words_mask = torch.zeros((1, args.max_tokens), dtype=torch.int32)

    class GlinerStaticWrapper(torch.nn.Module):
        def __init__(self, core_model):
            super().__init__()
            self.core_model = core_model

        def forward(self, input_ids, attention_mask, words_mask):
            ids_i64 = input_ids.to(torch.int64)
            mask_i64 = attention_mask.to(torch.int64)
            
            outputs = self.core_model(
                input_ids=ids_i64,
                attention_mask=mask_i64,
                return_dict=False
            )
            return outputs[0]

    wrapper = GlinerStaticWrapper(pytorch_model)
    
    torch.onnx.export(
        wrapper,
        (dummy_input_ids, dummy_attention_mask, dummy_words_mask),
        onnx_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask", "words_mask"],
        output_names=["logits"],
        dynamic_axes=None
    )
    print(f"Model ONNX wyeksportowany do: {onnx_path}")

    print("[KROK 4/5] Konwersja ONNX do TensorFlow SavedModel przy uzyciu onnx2tf...")
    try:
        import onnx2tf
        onnx2tf.convert(
            onnx_path=onnx_path,
            output_folder_path=tf_saved_model_path,
            copy_onnx_input_output_names_to_tflite=True,
            non_verbose=True
        )
    except Exception as e:
        print(f"Blad onnx2tf, proba awaryjnej konwersji przez tf2onnx: {e}")
        os.system(f"python -m tf2onnx.convert --output {tf_saved_model_path} --inputs-as-nchw input_ids:0")

    print("[KROK 5/5] Kompilacja do formatu LiteRT (.tflite) z precyzja FP16...")
    try:
        import tensorflow as tf
        converter = tf.lite.TFLiteConverter.from_saved_model(tf_saved_model_path)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        converter.inference_input_type = tf.int32
        converter.inference_output_type = tf.float32
        tflite_model = converter.convert()
        
        with open(tflite_path, "wb") as f:
            f.write(tflite_model)
            
        print(f"\n=======================================================")
        print(f"SUKCES: Model LiteRT GLiNER 2.5 został wygenerowany!")
        print(f"Plik: {tflite_path}")
        print(f"=======================================================\n")
    except Exception as e:
        print(f"Blad podczas konwersji TFLite: {e}")

if __name__ == "__main__":
    main()

