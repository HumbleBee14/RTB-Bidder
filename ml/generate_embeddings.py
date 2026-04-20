"""
Generate campaign embeddings and word embeddings for semantic ad targeting.

Uses sentence-transformers (all-MiniLM-L6-v2) to compute:
1. Campaign embeddings — one 384-dim vector per campaign (from keywords/description)
2. Word embeddings — one 384-dim vector per vocabulary word

Campaign embeddings are computed from the campaign's target segments and advertiser name.
Word embeddings cover common ad-tech vocabulary for context_text matching.

Usage:
  pip install sentence-transformers
  python ml/generate_embeddings.py
  → outputs ml/campaign_embeddings.json + ml/word_embeddings.json
"""

import json
from pathlib import Path

try:
    from sentence_transformers import SentenceTransformer
    HAS_SENTENCE_TRANSFORMERS = True
except ImportError:
    HAS_SENTENCE_TRANSFORMERS = False
    print("WARNING: sentence-transformers not installed. Using random embeddings for demo.")
    import numpy as np

SCRIPT_DIR = Path(__file__).parent
CAMPAIGNS_PATH = SCRIPT_DIR.parent / "src" / "main" / "resources" / "campaigns.json"
EMBEDDING_DIM = 384

# Common vocabulary for context_text embedding (ad-tech relevant words)
VOCABULARY = [
    # Sports & fitness
    "sports", "fitness", "running", "shoes", "gym", "workout", "athletic", "exercise",
    "training", "marathon", "cycling", "yoga", "basketball", "football", "soccer",
    # Technology
    "tech", "technology", "laptop", "phone", "software", "gaming", "computer", "app",
    "digital", "innovation", "AI", "programming", "developer", "startup", "device",
    # Travel
    "travel", "vacation", "flight", "hotel", "beach", "adventure", "tourism", "destination",
    "booking", "trip", "explore", "backpacking", "cruise", "resort", "sightseeing",
    # Finance
    "finance", "investment", "stock", "money", "banking", "savings", "crypto", "trading",
    "wealth", "budget", "insurance", "mortgage", "retirement", "portfolio", "income",
    # Food
    "food", "restaurant", "cooking", "recipe", "delivery", "pizza", "healthy", "organic",
    "vegan", "dining", "cafe", "breakfast", "lunch", "dinner", "snack",
    # Fashion
    "fashion", "clothing", "style", "shoes", "shopping", "designer", "outfit", "trend",
    "accessories", "dress", "shirt", "brand", "luxury", "casual", "streetwear",
    # Health
    "health", "wellness", "medical", "doctor", "fitness", "nutrition", "supplement",
    "mental", "therapy", "healthcare", "medicine", "vitamins", "sleep", "meditation",
    # Education
    "education", "learning", "course", "study", "school", "university", "student",
    "teaching", "online", "tutorial", "degree", "certification", "skill", "training",
    # Auto
    "auto", "car", "driving", "vehicle", "SUV", "electric", "automotive", "road",
    "engine", "sedan", "truck", "hybrid", "fuel", "dealership", "test-drive",
    # Entertainment
    "entertainment", "music", "movie", "game", "streaming", "concert", "podcast",
    "comedy", "theater", "festival", "show", "TV", "anime", "series", "drama",
    # General
    "best", "good", "looking", "need", "want", "buy", "find", "recommend", "cheap",
    "premium", "new", "review", "compare", "deal", "discount", "offer", "sale",
]


def load_campaigns():
    with open(CAMPAIGNS_PATH) as f:
        return json.load(f)


def campaign_text(campaign):
    """Build a descriptive text for the campaign for embedding."""
    parts = [campaign["advertiser"]]
    parts.extend(campaign.get("target_segments", []))
    return " ".join(parts)


def compute_embeddings_real(model):
    """Use sentence-transformers for real embeddings."""
    campaigns = load_campaigns()

    print("Computing campaign embeddings...")
    campaign_texts = {c["id"]: campaign_text(c) for c in campaigns}
    campaign_embeddings = {}
    for cid, text in campaign_texts.items():
        embedding = model.encode(text).tolist()
        campaign_embeddings[cid] = embedding
        print(f"  {cid}: '{text[:50]}' → [{embedding[0]:.4f}, {embedding[1]:.4f}, ...]")

    print(f"\nComputing word embeddings for {len(VOCABULARY)} vocabulary words...")
    word_embeddings = {}
    batch_embeddings = model.encode(VOCABULARY)
    for word, emb in zip(VOCABULARY, batch_embeddings):
        word_embeddings[word] = emb.tolist()

    return campaign_embeddings, word_embeddings


def compute_embeddings_random():
    """Fallback: random embeddings when sentence-transformers not available."""
    np.random.seed(42)
    campaigns = load_campaigns()

    campaign_embeddings = {}
    for c in campaigns:
        embedding = np.random.randn(EMBEDDING_DIM).astype(float).tolist()
        # Normalize
        norm = sum(x*x for x in embedding) ** 0.5
        campaign_embeddings[c["id"]] = [x / norm for x in embedding]

    word_embeddings = {}
    for word in VOCABULARY:
        embedding = np.random.randn(EMBEDDING_DIM).astype(float).tolist()
        norm = sum(x*x for x in embedding) ** 0.5
        word_embeddings[word] = [x / norm for x in embedding]

    return campaign_embeddings, word_embeddings


def main():
    if HAS_SENTENCE_TRANSFORMERS:
        print("Loading all-MiniLM-L6-v2 model...")
        model = SentenceTransformer("all-MiniLM-L6-v2")
        campaign_embeddings, word_embeddings = compute_embeddings_real(model)
    else:
        campaign_embeddings, word_embeddings = compute_embeddings_random()

    campaign_out = SCRIPT_DIR / "campaign_embeddings.json"
    with open(campaign_out, "w") as f:
        json.dump(campaign_embeddings, f)
    print(f"\nSaved {len(campaign_embeddings)} campaign embeddings → {campaign_out}")

    word_out = SCRIPT_DIR / "word_embeddings.json"
    with open(word_out, "w") as f:
        json.dump(word_embeddings, f)
    print(f"Saved {len(word_embeddings)} word embeddings → {word_out}")
    print(f"Embedding dim: {EMBEDDING_DIM}")


if __name__ == "__main__":
    main()
