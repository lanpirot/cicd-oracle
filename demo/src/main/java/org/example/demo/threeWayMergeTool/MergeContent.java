package org.example.demo.threeWayMergeTool;

public class MergeContent {
    private String baseContent;
    private final String yourContent;
    private final String theirContent;

    public MergeContent(String baseContent, String yourContent, String theirContent) {
        this.baseContent = baseContent;
        this.yourContent = yourContent;
        this.theirContent = theirContent;
    }

    public String getBaseContent() {
        return baseContent;
    }

    public void setBaseContent(String baseContent) {
        this.baseContent = baseContent;
    }

    public String getYourContent() {
        return yourContent;
    }

    public String getTheirContent() {
        return theirContent;
    }
}
