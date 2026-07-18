package com.hmdp.rag.embedding;

import java.util.List;

public interface IEmbeddingClient {
    List<float[]> embed(List<String> texts);
}
