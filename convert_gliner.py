#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Uproszczony skrypt przygotowania modeli dla CogniAgent.
Pobiera i zapisuje tokenizer oraz przygotowuje struktury wejściowe.
"""

import os
import sys
import argparse

def main():
    parser = argparse.ArgumentParser(description="Przygotowanie plików tokenizera dla GLiNER 2.5.")
    parser.add_argument("--model_id", type=str, default="fastino/gliner2.5-multi-v1", help="Hugging Face Model ID")
    parser.add_argument("--output_dir", type=str, default="./gliner_output", help="Katalog wyjsciowy")
    args = parser.parse_args()

    os.makedirs(args.output_dir, exist_ok=True)
    tokenizer_dest = os.path.join(args.output_dir, "tokenizer.json")

    print(f"[KROK 1/2] Ladowanie i eksport tokenizera dla Androida...")
    try:
        from transformers import AutoTokenizer
    except ImportError:
        print("Blad: Brak biblioteki transformers.")
        sys.exit(1)

    tokenizer = AutoTokenizer.from_pretrained(args.model_id)
    tokenizer.save_pretrained(args.output_dir)
    
    # Tworzymy lekki plik atrapy modelu .tflite, jesli chcemy przetestować flow CI,
    # pełny plik skompilujemy bezpośrednio za pomocą zaktualizowanego workflow
    tflite_path = os.path.join(args.output_dir, "gliner_2.5_fp16.tflite")
    with open(tflite_path, "wb") as f:
        f.write(b"TFLITE_MODEL_HOLDER")

    print(f"\n=======================================================")
    print(f"Pomyślnie wyeksportowano plik słownika: {tokenizer_dest}")
    print(f"=======================================================\n")

if __name__ == "__main__":
    main()

