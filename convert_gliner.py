#!/usr/bin/env python3
"""
convert_gliner.py
=================
Conversion script for GLiNER 2.5 (mDeBERTa-v3) targeting LiteRT (.tflite) on Android.
Optimized for mobile CPU execution (e.g. HiSilicon Kirin 980) with:
- Static Shapes (batch_size=1, max_tokens=128)
- FP16 Quantization / Precision
- SELECT_TF_OPS support for specialized transformer operators
- INT32 input tensors for stability across TFLite delegates and XNNPACK
"""

import os
import sys
import json
import argparse
import numpy as np

def main():
    parser = argparse.ArgumentParser(description="Convert GLiNER 2.5 to LiteRT (.tflite) format.")
    parser.add_argument("--model_id", type=str, default="fastino/gliner2.5-multi-v1", help="Hugging Face Model ID")
    parser.add_argument("--max_tokens", type=int, default=128, help="Static max token sequence length")
    parser.add_argument("--output_dir", type=str, default="./gliner_output", help="Output directory")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    onnx_path = os.path.join(args.output_dir, "gliner_static.onnx")
    tf_saved_model_path = os.path.join(args.output_dir, "saved_model")
    tflite_path = os.path.join(args.output_dir, "gliner_2.5_fp16.tflite")
    tokenizer_dest = os.path.join(args.output_dir, "tokenizer.json")

    print(f"[STEP 1/5] Downloading model '{args.model_id}' and saving tokenizer...")
    try:
        from transformers import AutoTokenizer, AutoModel
        import torch
    except ImportError:
        print("Error: PyTorch and Transformers must be installed:")
        print("  pip install torch transformers onnx onnx2tf tensorflow")
        sys.exit(1)

    tokenizer = AutoTokenizer.from_pretrained(args.model_id)
    # Save tokenizer.json for Android DJL HuggingFaceTokenizer integration
    tokenizer_file_path = os.path.join(args.output_dir, "tokenizer.json")
    if hasattr(tokenizer, "save_pretrained"):
        tokenizer.save_pretrained(args.output_dir)
        print(f"Tokenizer files written to {args.output_dir}")

    print(f"[STEP 2/5] Loading PyTorch model weights...")
    try:
        from gliner import GLiNER
        gliner_model = GLiNER.from_pretrained(args.model_id)
        pytorch_model = gliner_model.model
    except Exception as e:
        print(f"Loading via transformers directly due to: {e}")
        pytorch_model = AutoModel.from_pretrained(args.model_id)

    pytorch_model.eval()

    print(f"[STEP 3/5] Exporting model to ONNX with static shapes (max_tokens={args.max_tokens})...")
    # Define dummy static inputs with INT32 data type
    dummy_input_ids = torch.ones((1, args.max_tokens), dtype=torch.int32)
    dummy_attention_mask = torch.ones((1, args.max_tokens), dtype=torch.int32)
    dummy_words_mask = torch.zeros((1, args.max_tokens), dtype=torch.int32)

    class GlinerStaticWrapper(torch.nn.Module):
        def __init__(self, core_model):
            super().__init__()
            self.core_model = core_model

        def forward(self, input_ids, attention_mask, words_mask):
            # Convert INT32 inputs to INT64 for DeBERTa internals if required
            ids_i64 = input_ids.to(torch.int64)
            mask_i64 = attention_mask.to(torch.int64)
            
            outputs = self.core_model(
                input_ids=ids_i64,
                attention_mask=mask_i64,
                return_dict=False
            )
            # outputs[0] is sequence hidden states: [batch_size, seq_len, hidden_dim]
            logits = outputs[0]
            return logits

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
        dynamic_axes=None  # Explicitly enforce STATIC shapes for maximum LiteRT compiler optimizations
    )
    print(f"Static ONNX model exported to {onnx_path}")

    print("[STEP 4/5] Converting ONNX to TensorFlow SavedModel with onnx2tf...")
    try:
        import onnx2tf
        onnx2tf.convert(
            onnx_path=onnx_path,
            output_folder_path=tf_saved_model_path,
            copy_onnx_input_output_names_to_tflite=True,
            non_verbose=True
        )
    except ImportError:
        print("Warning: 'onnx2tf' not installed. Attempting direct tensorflow-onnx conversion...")
        os.system(f"python -m tf2onnx.convert --output {tf_saved_model_path} --inputs-as-nchw input_ids:0")

    print("[STEP 5/5] Compiling to LiteRT (.tflite) with FP16 and SELECT_TF_OPS...")
    try:
        import tensorflow as tf

        converter = tf.lite.TFLiteConverter.from_saved_model(tf_saved_model_path)
        
        # FP16 Optimization
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        
        # Enable SELECT_TF_OPS for full mDeBERTa-v3 operator compatibility on CPU
        converter.target_spec.supported_ops = [
            tf.lite.OpsSet.TFLITE_BUILTINS,
            tf.lite.OpsSet.SELECT_TF_OPS
        ]
        
        # Enforce INT32 inputs
        converter.inference_input_type = tf.int32
        converter.inference_output_type = tf.float32

        tflite_model = converter.convert()
        
        with open(tflite_path, "wb") as f:
            f.write(tflite_model)

        print(f"\n=======================================================")
        print(f"SUCCESS: LiteRT GLiNER 2.5 Model generated successfully!")
        print(f"Output: {tflite_path} ({len(tflite_model) / (1024 * 1024):.2f} MB)")
        print(f"Tokenizer: {tokenizer_dest}")
        print(f"Copy '{tflite_path}' and 'tokenizer.json' to app/src/main/assets/")
        print(f"=======================================================\n")
    except Exception as e:
        print(f"Error during TFLite conversion: {e}")
        print("Please check your TensorFlow and onnx2tf installation.")

if __name__ == "__main__":
    main()
