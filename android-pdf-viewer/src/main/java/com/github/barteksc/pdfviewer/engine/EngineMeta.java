package com.github.barteksc.pdfviewer.engine;

/**
 * Engine-neutral document information-dictionary fields. Any of them may be null when the
 * document does not carry them.
 */
public class EngineMeta {

    private final String title;
    private final String author;
    private final String subject;
    private final String keywords;
    private final String creator;
    private final String producer;
    private final String creationDate;
    private final String modDate;

    public EngineMeta(String title, String author, String subject, String keywords, String creator,
                      String producer, String creationDate, String modDate) {
        this.title = title;
        this.author = author;
        this.subject = subject;
        this.keywords = keywords;
        this.creator = creator;
        this.producer = producer;
        this.creationDate = creationDate;
        this.modDate = modDate;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getSubject() {
        return subject;
    }

    public String getKeywords() {
        return keywords;
    }

    public String getCreator() {
        return creator;
    }

    public String getProducer() {
        return producer;
    }

    public String getCreationDate() {
        return creationDate;
    }

    public String getModDate() {
        return modDate;
    }
}
