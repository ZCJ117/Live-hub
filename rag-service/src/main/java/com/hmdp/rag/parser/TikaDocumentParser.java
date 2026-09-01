package com.hmdp.rag.parser;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

@Component
@Slf4j
public class TikaDocumentParser implements IDocumentParser {

    //NOTE 这里使用Apache Tika来解析文档内容。把各种文档上传，Tika自动识别文档类型并提取文本。

    @Override
    public String parse(InputStream inputStream, String filename) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        ParseContext context = new ParseContext();

        try {
            parser.parse(inputStream, handler, metadata, context);
            String text = handler.toString().trim();
            log.info("Parsed '{}': {} chars extracted", filename, text.length());
            return text;
        } catch (IOException | SAXException | TikaException e) {
            log.error("Failed to parse document: {}", filename, e);
            throw new Exception("文档解析失败: " + filename, e);
        }
    }
}
