package app.photoprompt.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class PromptFormatTest {
    @Test public void separatesDescriptionAndCopyablePrompt() {
        PromptFormat r = PromptFormat.parse("ANALYSIS:\nSoft light.\nPROMPT:\nA portrait in soft window light.");
        assertEquals("Soft light.", r.analysis);
        assertEquals("A portrait in soft window light.", r.prompt);
        assertFalse(r.fallback);
    }
    @Test public void acceptsMarkdownHeadingsFromModel() {
        PromptFormat r = PromptFormat.parse("**ANALYSIS:**\nMuted colors.\n### **Generation Prompt:**\nA muted street photograph.");
        assertEquals("Muted colors.", r.analysis);
        assertEquals("A muted street photograph.", r.prompt);
        assertFalse(r.fallback);
    }
    @Test public void marksUnstructuredDescriptionAsDraftWithoutInventingContent() {
        PromptFormat r = PromptFormat.parse("A red bicycle beside a stone wall.");
        assertTrue(r.fallback);
        assertEquals(r.analysis, r.prompt);
        assertEquals("A red bicycle beside a stone wall.", r.prompt);
    }
    @Test public void ignoresWordPromptInsideAnOrdinarySentence() {
        PromptFormat r = PromptFormat.parse("The sign says prompt: something.\nPROMPT: A street scene.");
        assertEquals("A street scene.", r.prompt);
        assertEquals("The sign says prompt: something.", r.analysis);
    }
    @Test public void neverInventsAResultForEmptyOutput() {
        assertEquals("", PromptFormat.parse(null).prompt);
        assertTrue(PromptFormat.parse("  ").fallback);
    }
    @Test public void handlesEmptyPromptWithoutSilentlyClaimingSuccess() {
        assertTrue(PromptFormat.parse("ANALYSIS: A forest.\nPROMPT:").fallback);
    }
    @Test public void copiedReportKeepsProvenanceAndUserEdits() {
        String report = PromptFormat.fullReport("Camera model: Not available", "2:1", "Side light.", "My edited prompt.");
        assertTrue(report.contains("Camera model: Not available"));
        assertTrue(report.contains("estimates, not verified facts"));
        assertTrue(report.contains("My edited prompt."));
        assertFalse(report.contains("Canon"));
    }
}
