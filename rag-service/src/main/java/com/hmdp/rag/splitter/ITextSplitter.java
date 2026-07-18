package com.hmdp.rag.splitter;

import java.util.List;

public interface ITextSplitter {
    List<String> split(String text, int chunkSize, int overlap);
}
