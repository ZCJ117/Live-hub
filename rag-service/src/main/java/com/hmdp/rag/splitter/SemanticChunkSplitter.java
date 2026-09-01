package com.hmdp.rag.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

//NOTE SemanticChunkSplitter ：文本分块。 chunk（块） 切的逻辑：按段落切分

@Component
@Slf4j
public class SemanticChunkSplitter implements ITextSplitter {

    //NOTE 这里使用正则表达式来匹配段落分隔符（空行），用于将文本按段落进行切分。
    private static final Pattern PARAGRAPH_SEP = Pattern.compile("\\n\\s*\\n");

    @Override
    public List<String> split(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        //按空行分割成段落
        String[] paragraphs = PARAGRAPH_SEP.split(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        //  逐段合并，超过 chunkSize 就切一刀
        for (String para : paragraphs) {
            String cleaned = para.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) continue;

            if (current.length() + cleaned.length() + 1 > chunkSize && current.length() > 0) {
                chunks.add(current.toString().trim());
                if (overlap > 0 && current.length() > overlap) {
                    String overlapText = current.substring(Math.max(0, current.length() - overlap));
                    current = new StringBuilder(overlapText + " " + cleaned);
                } else {
                    current = new StringBuilder(cleaned);
                }
            } else {
                if (current.length() > 0) current.append(" ");
                current.append(cleaned);
            }
        }

        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }

        log.debug("Split text ({} chars) into {} chunks (size={}, overlap={})",
                text.length(), chunks.size(), chunkSize, overlap);
        return chunks;
    }
}
