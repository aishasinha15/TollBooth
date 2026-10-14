"""
A tiny web service that answers one question: are these two questions duplicates?

It runs cross-encoder/quora-distilroberta-base, a model trained on Quora's duplicate-question
pairs. Unlike an embedding model (which turns each question into numbers SEPARATELY and then
compares them), a cross-encoder reads BOTH questions together, word by word, so it can notice
that "3-day" vs "5-day" or "Goa" vs "Manali" changes the meaning.

POST /score   {"pairs": [{"a": "...", "b": "..."}, ...]}
           -> {"model": "...", "scores": [0.97, 0.03, ...]}   (0 = different, 1 = duplicate)
GET  /health  -> {"status": "ok"}
"""
import time

import torch
from fastapi import FastAPI
from pydantic import BaseModel, Field
from sentence_transformers import CrossEncoder

MODEL_NAME = "cross-encoder/quora-distilroberta-base"

# Loaded once at startup (from the copy saved in the image), then reused for every request
model = CrossEncoder(MODEL_NAME)
app = FastAPI(title="Tollbooth duplicate checker")


class Pair(BaseModel):
    a: str
    b: str


class ScoreRequest(BaseModel):
    pairs: list[Pair] = Field(max_length=100)


@app.post("/score")
def score(request: ScoreRequest):
    if not request.pairs:
        return {"model": MODEL_NAME, "scores": [], "ms": 0}
    start = time.perf_counter()
    # Sigmoid turns the model's raw output into a 0..1 "probability of duplicate"
    scores = model.predict(
        [(p.a, p.b) for p in request.pairs],
        activation_fct=torch.nn.Sigmoid(),
    )
    ms = round((time.perf_counter() - start) * 1000)
    return {"model": MODEL_NAME, "scores": [round(float(s), 4) for s in scores], "ms": ms}


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}
