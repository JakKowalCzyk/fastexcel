/*
 * Copyright 2016 Dhatim.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dhatim.fastexcel;

import com.github.rzymek.opczip.OpcOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;


/**
 * A {@link Workbook} contains one or more {@link Worksheet} objects.
 */
public class Workbook {

    private final String applicationName;
    private final String applicationVersion;
    private final List<Worksheet> worksheets = new ArrayList<>();
    private final StringCache stringCache = new StringCache();
    private final StyleCache styleCache = new StyleCache();
    private final OpcOutputStream os;
    private final Writer writer;

    /**
     * Constructor.
     *
     * @param os Output stream eventually holding the serialized workbook.
     * @param applicationName Name of the application which generated this
     * workbook.
     * @param applicationVersion Version of the application. Ignored if
     * {@code null}. Refer to
     * <a href="https://msdn.microsoft.com/en-us/library/documentformat.openxml.extendedproperties.applicationversion(v=office.14).aspx">this
     * page</a> for details.
     */
    public Workbook(OutputStream os, String applicationName, String applicationVersion) {
        this.os = new OpcOutputStream(os);
        /* Tests showed that:
         * The default (-1) is level 6
         * Level 4 gives best size and very good time
         * Level 2 gives best time and very good size
         * see https://github.com/dhatim/fastexcel/pull/65
         */
        setCompressionLevel(4);
        this.writer = new Writer(this.os);
        this.applicationName = Objects.requireNonNull(applicationName);

        // Check application version
        if (applicationVersion != null && !applicationVersion.matches("\\d{1,2}\\.\\d{1,4}")) {
            throw new IllegalArgumentException("Application version must be of the form XX.YYYY");
        }
        this.applicationVersion = applicationVersion;
    }

    /**
     * Sets the compression level of the xlsx.
     * An xlsx file is a standard zip archive consisting of xml files.
     * Default compression is 4.
     *
     * @param level the compression level (0-9)
     */
    public void setCompressionLevel(int level) {
        this.os.setLevel(level);
    }

    /**
     * Sort the current worksheets with the given Comparator
     *
     * @param comparator The Comparator used to sort the worksheets
     */
    public void sortWorksheets(Comparator<Worksheet> comparator) {
        worksheets.sort(comparator);
    }

    /**
     * Complete workbook generation: this writes worksheets and additional files
     * as zip entries to the output stream.
     *
     * @throws IOException In case of I/O error.
     */
    public void finish() throws IOException {
        if (worksheets.isEmpty()) {
            throw new IllegalArgumentException("A workbook must contain at least one worksheet.");
        }
        for (Worksheet ws : worksheets) {
            ws.finish();
        }

        writeComments();

        writeFile("[Content_Types].xml", w -> {
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>");
            if(hasComments()){
                w.append("<Default ContentType=\"application/vnd.openxmlformats-officedocument.vmlDrawing\" Extension=\"vml\"/>");
            }
            w.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
            for (Worksheet ws : worksheets) {
                int index = getIndex(ws);
                w.append("<Override PartName=\"/xl/worksheets/sheet").append(index).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                if(!ws.comments.isEmpty()) {
                    w.append("<Override ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml\" PartName=\"/xl/comments").append(index).append(".xml\"/>");
                    w.append("<Override ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\" PartName=\"/xl/drawings/drawing").append(index).append(".xml\"/>");
                }
            }
            w.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/><Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/></Types>");
        });
        writeFile("docProps/app.xml", w -> w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>").appendEscaped(applicationName).append("</Application>").append(applicationVersion == null ? "" : ("<AppVersion>" + applicationVersion + "</AppVersion>")).append("</Properties>"));
        writeFile("docProps/core.xml", w -> w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dcterms:created xsi:type=\"dcterms:W3CDTF\">").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("Z</dcterms:created><dc:creator>").appendEscaped(applicationName).append("</dc:creator></cp:coreProperties>"));
        writeFile("_rels/.rels", w -> w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"));

        writeWorkbookFile();

        writeFile("xl/_rels/workbook.xml.rels", w -> {
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"sharedStrings.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\"/><Relationship Id=\"rId2\" Target=\"styles.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\"/>");
            for (Worksheet ws : worksheets) {
                w.append("<Relationship Id=\"rId").append(getIndex(ws) + 2).append("\" Target=\"worksheets/sheet").append(getIndex(ws)).append(".xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"/>");
            }
            w.append("</Relationships>");
        });
        writeFile("xl/sharedStrings.xml", stringCache::write);
        writeFile("xl/styles.xml", styleCache::write);
        this.os.finish();
    }

    /**
     * @return true when any sheet has any comments
     */
    private boolean hasComments() {
        for (Worksheet ws : worksheets) {
            if (!ws.comments.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void writeComments() throws IOException {
        for (Worksheet ws : worksheets) {
            if (ws.comments.isEmpty()) {
                continue;
            }
            int index = getIndex(ws);
            writeFile("xl/comments" + index + ".xml", ws.comments::writeComments);
            writeFile("xl/drawings/vmlDrawing" + index + ".vml", ws.comments::writeVmlDrawing);
            writeFile("xl/drawings/drawing" + index + ".xml", ws.comments::writeDrawing);

            writeFile("xl/worksheets/_rels/sheet" + index + ".xml.rels", w -> {
                w.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>");
                w.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
                w.append("<Relationship Id=\"d\" Target=\"../drawings/drawing" + index + ".xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\"/>");
                w.append("<Relationship Id=\"c\" Target=\"../comments" + index + ".xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\"/>");
                w.append("<Relationship Id=\"v\" Target=\"../drawings/vmlDrawing" + index + ".vml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing\"/>");
                w.append("</Relationships>");
            });

        }
    }

    /**
     * Writes the {@code xl/workbook.xml} file to the zip.
     * @throws IOException If an I/O error occurs.
     */
    private void writeWorkbookFile() throws IOException {
        writeFile("xl/workbook.xml", w -> {
            w.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<workbook " +
                            "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<workbookPr date1904=\"false\"/>" +
                        "<bookViews>" +
                            "<workbookView activeTab=\"0\"/>" +
                        "</bookViews>" +
                        "<sheets>");

            for (Worksheet ws : worksheets) {
                writeWorkbookSheet(w, ws);
            }
            w.append("</sheets>");
            /** Defining repeating rows and columns for the print setup...
             *  This is defined for each sheet separately 
             * (if there are any repeating rows or cols in the sheet at all) **/
    
            for (Worksheet ws : worksheets) {
                String defineName = "";
                if(ws.getRepeatingCols() != null) {
                    String startCol = Range.colToString(ws.getRepeatingCols().get(0));
                    String endCol = Range.colToString(ws.getRepeatingCols().get(1));
                    defineName += ws.getName() + "!$" + startCol + ":$" + endCol;
                }
                if(ws.getRepeatingRows() != null) {
                    if (ws.getRepeatingCols() != null) defineName += ",";
                    int startRow = 1 + ws.getRepeatingRows().get(0);
                    int endRow = 1 + ws.getRepeatingRows().get(1);
                    defineName += ws.getName() + "!$" + startRow + ":$" + endRow;
                }
                if (!defineName.equals("")) {
                    w.append("<definedNames>");
                    w.append("<definedName function=\"false\" " + 
                                "hidden=\"false\" " +
                                "localSheetId=\"0\" " + 
                                "name=\"_xlnm.Print_Titles\" " + 
                                "vbProcedure=\"false\">");
                    w.append(defineName);
                    w.append("</definedName>");
                    w.append("</definedNames>");
                }
            }
            w.append("</workbook>");
        });
    }

    /**
     * Writes a {@code sheet} tag to the writer.
     * @param w The writer to write to
     * @param ws The WorkSheet that is resembled by the {@code sheet} tag.
     * @throws IOException If an I/O error occurs.
     */
    private void writeWorkbookSheet(Writer w, Worksheet ws) throws IOException {
        w.append("<sheet name=\"").appendEscaped(ws.getName()).append("\" r:id=\"rId").append(getIndex(ws) + 2)
                .append("\" sheetId=\"").append(getIndex(ws));

        if (ws.getVisibilityState() != null) {
            w.append("\" state=\"").append(ws.getVisibilityState().getName());
        }

        w.append("\"/>");
    }

    /**
     * Write a new file as a zip entry to the output writer.
     *
     * @param name File name.
     * @param consumer Output writer consumer, producing file contents.
     * @throws IOException If an I/O error occurs.
     */
    void writeFile(String name, ThrowingConsumer<Writer> consumer) throws IOException {
        synchronized (os) {
            beginFile(name);
            consumer.accept(writer);
            endFile();
        }
    }

    Writer beginFile(String name) throws IOException {
        os.putNextEntry(new ZipEntry(name));
        return writer;
    }
    void endFile() throws IOException {
        writer.flush();
        os.closeEntry();
    }

    /**
     * Cache the given string.
     *
     * @param s String to cache.
     * @return Cached string.
     */
    CachedString cacheString(String s) {
        return stringCache.cacheString(s);
    }

    /**
     * Merge given style attributes with cached style.
     *
     * @param currentStyle Current (cached) style index, 0 if none.
     * @param numberingFormat Numbering format.
     * @param font Font attributes.
     * @param fill Fill attributes.
     * @param border Border attributes.
     * @param alignment Alignment attributes.
     * @return Cached style index.
     */
    int mergeAndCacheStyle(int currentStyle, String numberingFormat, Font font, Fill fill, Border border, Alignment alignment, Protection protection) {
        return styleCache.mergeAndCacheStyle(currentStyle, numberingFormat, font, fill, border, alignment, protection);
    }

    /**
     * Cache shading color for alternate rows.
     *
     * @param fill Fill pattern attributes.
     * @return Cached fill pattern index.
     */
    int cacheShadingFillColor(Fill fill) {
        return styleCache.cacheDxf(fill);
    }

    /**
     * Get unique index of a worksheet.
     *
     * @param ws Worksheet. It must have been created previously by calling
     * {@link #newWorksheet(java.lang.String)} on this workbook.
     * @return Worksheet index.
     */
    int getIndex(Worksheet ws) {
        synchronized (worksheets) {
            return worksheets.indexOf(ws) + 1;
        }
    }

    /**
     * Create a new worksheet in this workbook.
     *
     * @param name Name of the new worksheet.
     * @return The new blank worksheet.
     */
    public Worksheet newWorksheet(String name) {
        // Replace chars forbidden in worksheet names (backslahses and colons) by dashes
        String sheetName = name.replaceAll("[/\\\\?*\\]\\[:]", "-");

        // Maximum length worksheet name is 31 characters
        if (sheetName.length() > 31) {
            sheetName = sheetName.substring(0, 31);
        }

        synchronized (worksheets) {
            // If the worksheet name already exists, append a number
            int number = 1;
            Set<String> names = worksheets.stream().map(Worksheet::getName).collect(Collectors.toSet());
            while (names.contains(sheetName)) {
                String suffix = String.format(Locale.ROOT, "_%d", number);
                if (sheetName.length() + suffix.length() > 31) {
                    sheetName = sheetName.substring(0, 31 - suffix.length()) + suffix;
                } else {
                    sheetName += suffix;
                }
                ++number;
            }
            Worksheet worksheet = new Worksheet(this, sheetName);
            worksheets.add(worksheet);
            return worksheet;
        }
    }
}
