package com.hmdp.rag.parser;

import java.io.InputStream;

public interface IDocumentParser {
    String parse(InputStream inputStream, String filename) throws Exception;
}
