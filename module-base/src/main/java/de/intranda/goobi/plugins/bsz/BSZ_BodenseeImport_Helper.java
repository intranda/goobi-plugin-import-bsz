package de.intranda.goobi.plugins.bsz;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IOpacPlugin;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.Setter;
import lombok.extern.log4j.Log4j;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@Log4j
public class BSZ_BodenseeImport_Helper {
    private String basic_name;
    private String catalogue = "BSZ-BW";
    private String basic_folder = "/opt/digiverso/BSZ/Bodensee/";

    private String image_folder_extension = "_" + "tif";
    private String image_file_prefix_to_remove;
    private String image_file_suffiix_to_use = ".jpg";

    private String bsz_import_sql_file;
    private String bsz_import_folder;
    private String ppn_volume;
    private Prefs prefs;
    private String ppn;
    private String tempFolder;
    private String title;
    @Setter
    private boolean createIssues = true;
    private boolean separateBookletIds = false;

    public BSZ_BodenseeImport_Helper(String inBasicName, boolean inSeparateBooketIds){
        basic_name = inBasicName;
        image_file_prefix_to_remove = "/data/kebweb/" + basic_name + "/";
        bsz_import_sql_file = basic_folder + basic_name + ".sql";
        bsz_import_folder = basic_folder + basic_name + "/";
        separateBookletIds = inSeparateBooketIds;
    }

    public void prepare(Prefs prefs, String ppn, String tempFolder, String title) {
        this.prefs = prefs;
        this.ppn = ppn;
        this.tempFolder = tempFolder;
        this.title = title;
    }

    /**
     * Read all years from the given JSON file to pass back an ordered list of all
     * importable years for the mass import plugin GUI
     * 
     * @return List<String> of years
     */
    public List<String> getYearsFromJson() {
        // read sql file to list all years
        LinkedHashSet<String> years = new LinkedHashSet<>();

        try {
            File f = new File (bsz_import_sql_file);
            List<String> lines = FileUtils.readLines(f, "UTF-8");
            for (String line : lines) {
                if (line.startsWith("INSERT INTO")){
                    BSZ_BodenseeImport_Element element = convertSqlToElement(line);

                    String isn = "";
                    if (StringUtils.isNumeric(element.getIssueNumber())){
                        isn = "_" + element.getIssueNumber();
                    }
                    if (separateBookletIds){
                        years.add(element.getJahr() + isn + "_" + element.getBookletid());
                    } else{
                        years.add(element.getJahr() + isn);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Problem occured while reading the sql file for " + basic_name + " import", e);
        }

        ArrayList<String> mylist = new ArrayList<>(years);
        Collections.sort(mylist);
        return mylist;
    }

    /**
     * Method to convert a given SQL insert line into a BSZ Element
     * 
     * @param inline the insert statement line of sql to parse
     * @return a new {@link BSZ_BodenseeImport_Element}
     */
    private BSZ_BodenseeImport_Element convertSqlToElement(String inline){
        String line = inline.substring(inline.indexOf("VALUES (") + 8, inline.lastIndexOf(")"));
        String[] parts = line.split(",");
        BSZ_BodenseeImport_Element element = new BSZ_BodenseeImport_Element();
        element.setPageid(parts[0].trim().replaceAll("'", ""));
        element.setBookletid(parts[1].trim().replaceAll("'", ""));
        element.setJournalid(parts[2].trim().replaceAll("'", ""));
        element.setLfnr(parts[3].trim().replaceAll("'", ""));
        element.setJahr(parts[4].trim().replaceAll("'", ""));
        element.setLabel(parts[5].trim().replaceAll("'", ""));
        element.setJpg(parts[6].trim().replaceAll("'", ""));
        return element;
    }

    /**
     * Generates {@link ImportObject} element for given {@link Record} and store it in the list of
     * {@link ImportObject} to be imported then afterwards
     * This method is the main entry to to the catalogue import of bibliographic data, the generation
     * of a {@link Fileformat} and to enrich this with more bibliographic data, to read the logical
     * structure from the JSON file and assign pages with page labes to these structural elements
     * 
     * @param answer List of {@link ImportObject} where to add the generated {@link ImportObject} to
     * @param record Record with information about the item to do the import for
     */
    public void generateImportObject(List<ImportObject> answer, Record record) {
        // generate an ImportObject for each selected year and add it to the result list later on
        ImportObject importObjectYear = new ImportObject();
        ppn_volume = ppn + "_" + record.getId();
        ppn_volume = ppn_volume.replaceAll("\\+", "_");
        ppn_volume = ppn_volume.replaceAll("\\.", "_");
        String metsFileName = tempFolder + ppn_volume + ".xml";
        importObjectYear.setMetsFilename(metsFileName);
        String procTitle = basic_name + "_" + ppn_volume;
        String regex = ConfigurationHelper.getInstance().getProcessTitleReplacementRegex();
        procTitle = procTitle.replaceAll(regex, "");
        importObjectYear.setProcessTitle(procTitle);

        try {
            // request the object from the catalogue and generate a FileFormat
            Fileformat fileformat = createFileFormat();
            if (fileformat != null) {

                // create physical docstruct
                try {
                    DigitalDocument dd = fileformat.getDigitalDocument();
                    DocStructType docstructBoundBook = prefs.getDocStrctTypeByName("BoundBook");
                    DocStruct physical = dd.createDocStruct(docstructBoundBook);
                    Metadata pathimagefiles = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
                    pathimagefiles.setValue(ppn_volume);
                    physical.addMetadata(pathimagefiles);
                    dd.setPhysicalDocStruct(physical);

                } catch (PreferencesException | TypeNotAllowedForParentException
                        | MetadataTypeNotAllowedException e1) {
                    log.error("Errow while doing the massimport in " + this.getClass().getName(), e1);
                }

                try {
                    // add all issues and the correct pages there
                    addAllIssues(fileformat, record.getId(),importObjectYear.getProcessTitle());

                    // write Mets file into temp folder of Goobi to let it be imported afterwards
                    MetsMods mm = new MetsMods(prefs);
                    mm.setDigitalDocument(fileformat.getDigitalDocument());
                    log.debug("Writing '" + metsFileName + "' into given folder...");
                    mm.write(metsFileName);

                    importObjectYear.setImportReturnValue(ImportReturnValue.ExportFinished);
                } catch (IOException e) {
                    log.error("IOException during the massimport " + this.getClass().getName(), e);
                    importObjectYear.setErrorMessage(e.getMessage());
                    importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
                } catch (WriteException e) {
                    log.error("WriteException during the massimport " + this.getClass().getName(), e);
                    importObjectYear.setErrorMessage(e.getMessage());
                    importObjectYear.setImportReturnValue(ImportReturnValue.WriteError);
                } catch (UGHException e) {
                    log.error("PreferencesException during the massimport " + this.getClass().getName(), e);
                    importObjectYear.setErrorMessage(e.getMessage());
                    importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
                }
            } else {
                importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
            }

            answer.add(importObjectYear);
        } catch (ImportPluginException e) {
            log.error("ImportPluginException during the massimport in " + this.getClass().getName(), e);
        }
    }

    /**
     * Method to do the catalogue request for a given identifier and to generate a {@link Fileformat} out of it
     * During the creation of the {@link Fileformat} it is enriched with an updated identifier, a viewer
     * sub theme and the right digital collection assignements
     * 
     * @return {@link Fileformat} to use it as METS file afterwards
     * @throws ImportPluginException
     */
    private Fileformat createFileFormat() throws ImportPluginException {
        try {
            ConfigOpacCatalogue coc = ConfigOpac.getInstance().getCatalogueByName(catalogue);
            IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
            Fileformat myRdf = myImportOpac.search("12", ppn, coc, prefs);

            DocStruct ds = myRdf.getDigitalDocument().getLogicalDocStruct();
            // change existing digital ppn to have a prefix
            adaptIdentifier(ds, "CatalogIDDigital", ppn);
            // change existing source ppn to have a prefix
            adaptIdentifier(ds, "CatalogIDSource", ppn);
            // add viewer theme
            addMetadata(ds, "ViewerSubTheme", "bsz-st-bodenseebibliotheken");
            // add collections
            addMetadata(ds, "singleDigCollection", "ZS_RegioBodensee");

            // assign a ppn digital to the child docstruct (volume)
            if (ds.getType().isAnchor()) {
                DocStruct child = ds.getAllChildren().get(0);
                adaptIdentifier(child, "CatalogIDDigital", ppn_volume);
                adaptIdentifier(child, "CatalogIDSource", ppn_volume);
            }

            return myRdf;
        } catch (Exception e) {
            log.error("Exception while creating the FileFormat", e);
        }
        return null;
    }

    /**
     * Method to extract a given pdf file if it exists for the volume as individual pages
     * 
     * @param inYear the year to use for the extraction of data from the JSON result
     * @param inProcessTitle the process title to use for copying the final results at the end
     * 
     * @throws IOException
     * @throws COSVisitorException
     */
    private void extractPdf(String inYear, String inProcessTitle, List<BSZ_BodenseeImport_Element> elements) throws IOException{
        File targetFolderPdfSingles = new File(tempFolder + ppn_volume + File.separator + "ocr"
                + File.separator + inProcessTitle + "_pdf");
        targetFolderPdfSingles.mkdirs();
        // copy pdf files into right place in tmp folder
        int pdfCounter = 1;

        List<Path> allMyFiles = new ArrayList();
        //		allMyFiles = NIOFileUtils.listFiles(bsz_import_folder,PDF_FILTER);
        if (elements.size()>0){
            String firstImage = elements.get(0).getJpg().substring(image_file_prefix_to_remove.length() -1);
            firstImage = firstImage.substring(0, firstImage.lastIndexOf("/"));
            File folderPath = new File(bsz_import_folder, firstImage);
            allMyFiles.addAll(StorageProvider.getInstance().listFiles(folderPath.getAbsolutePath(),PDF_FILTER));
        }
        for (Path entry : allMyFiles) {
            log.debug(entry.toAbsolutePath());
            File f = entry.toFile();
            //			if (f.getName().endsWith(".pdf") && f.getName().toLowerCase().contains("j" + inYear + "")) {

            // aglv, alem, alst, bgvh, blbg, dosc, fmgv, heim, jffv, jvlm, klwa, kumm, mojb, mosr, rhet, tivo, vona, vool, vora, vovo, wahb, wahe jall, vgeb.
            if (f.getAbsolutePath().contains("/aglv/") ||
                    f.getAbsolutePath().contains("/alem/") ||
                    f.getAbsolutePath().contains("/alst/") ||
                    f.getAbsolutePath().contains("/bgvh/") ||
                    f.getAbsolutePath().contains("/bgwh/") ||
                    f.getAbsolutePath().contains("/blgb/") ||
                    f.getAbsolutePath().contains("/dosc/") ||
                    f.getAbsolutePath().contains("/fmgv/") ||
                    f.getAbsolutePath().contains("/heim/") ||
                    f.getAbsolutePath().contains("/jffv/") ||
                    f.getAbsolutePath().contains("/jvlm/") ||
                    f.getAbsolutePath().contains("/klwa/") ||
                    f.getAbsolutePath().contains("/kult/") ||
                    f.getAbsolutePath().contains("/kumm/") ||
                    f.getAbsolutePath().contains("/mojb/") ||
                    f.getAbsolutePath().contains("/mosr/") ||
                    f.getAbsolutePath().contains("/rhet/") ||
                    f.getAbsolutePath().contains("/tivo/") ||
                    f.getAbsolutePath().contains("/vona/") ||
                    f.getAbsolutePath().contains("/vool/") ||
                    f.getAbsolutePath().contains("/vora/") ||
                    f.getAbsolutePath().contains("/vovo/") ||
                    f.getAbsolutePath().contains("/wahb/") ||
                    f.getAbsolutePath().contains("/wahe/") ||
                    //					f.getAbsolutePath().contains("/sgja/") ||
                    f.getAbsolutePath().contains("/jall/") ||
                    f.getAbsolutePath().contains("/vgeb/")) {
                // create single page pdf files
                PDDocument inputDocument = PDDocument.load(f);
                for (int page = 1; page <= inputDocument.getNumberOfPages(); ++page) {
                    PDPage pdPage = inputDocument.getDocumentCatalog().getPages().get(page - 1);
                    PDDocument outputDocument = new PDDocument();
                    outputDocument.addPage(pdPage);
                    File pdfout = new File(targetFolderPdfSingles, String.format("%08d", pdfCounter++) + ".pdf");
                    outputDocument.save(pdfout.getAbsolutePath());
                    outputDocument.close();
                }
                inputDocument.close();
            }
        }
    }

    public static final DirectoryStream.Filter<Path> PDF_FILTER = new DirectoryStream.Filter<Path>() {
        @Override
        public boolean accept(Path path) {
            boolean fileOk = false;
            String name = path.getFileName().toString();
            if (name.toLowerCase().endsWith(".pdf")){
                fileOk = true;
            }
            return fileOk;
        }
    };


    /**
     * Method to add all issues into the the generated {@link Fileformat} incl. page labels, structural sub elements
     * and page assignements. In case a pdf file exists for a the volume too it is extracted into individual pages
     * 
     * @param ff {@link Fileformat} to use for the enrichtment
     * @param inYearAndIssueNumber the year to use for the extraction of data from the JSON result
     * @param inProcessTitle the process title to use for copying the final results at the end
     * 
     * @throws IOException
     * @throws UGHException
     * @throws COSVisitorException
     */
    private void addAllIssues(Fileformat ff, String inYearAndIssueNumber, String inProcessTitle) throws IOException, UGHException {
        File targetFolderImages = new File(tempFolder + ppn_volume + File.separator + "images"
                + File.separator + inProcessTitle + image_folder_extension);
        targetFolderImages.mkdirs();

        DocStruct physicaldocstruct = ff.getDigitalDocument().getPhysicalDocStruct();
        DocStruct volume = ff.getDigitalDocument().getLogicalDocStruct().getAllChildren().get(0);
        DocStructType issueType = prefs.getDocStrctTypeByName("PeriodicalIssue");
        DocStruct issue = null;
        String lastIssue = "";
        int physicalPageNumber = 1;


        if (createIssues){
            ff.getDigitalDocument().getLogicalDocStruct().getAllChildren().remove(volume);
        }else{
            String justYear = inYearAndIssueNumber;
            if (justYear.contains("_")){
                justYear=justYear.substring(0,justYear.indexOf("_"));
            }
            // add title to volume
            addMetadata(volume, "TitleDocMain", title + " " + justYear);
            // add current number
            addMetadata(volume, "CurrentNo", justYear);
            // add current number sorting
            addMetadata(volume, "CurrentNoSorting", justYear);
            // add viewer theme
            addMetadata(volume, "ViewerSubTheme", "bsz-st-bodenseebibliotheken");
            // add collections
            addMetadata(volume, "singleDigCollection", "ZS_RegioBodensee");
            // add publication year
            addMetadata(volume, "PublicationYear", justYear);
        }


        DocStruct logicalDocstruct = ff.getDigitalDocument().getLogicalDocStruct();
        if (logicalDocstruct.getType().isAnchor()) {
            if (logicalDocstruct.getAllChildren() != null && logicalDocstruct.getAllChildren().size() > 0) {
                logicalDocstruct = logicalDocstruct.getAllChildren().get(0);
            }
        }

        // run through all JSON elements to start the import now and add all issues and pages from one year to this volume
        List<BSZ_BodenseeImport_Element> elements = readElementsFromSql(inYearAndIssueNumber);

        // extract given pdf file
        extractPdf(inYearAndIssueNumber,inProcessTitle, elements);

        for (BSZ_BodenseeImport_Element element : elements) {

            String issueForTitle = "";
            String issueForDate = "01";
            if (StringUtils.isNumeric(element.getIssueNumber())){
                issueForTitle = "-" + element.getIssueNumber();
                issueForDate = element.getIssueNumber();
            }

            String year = element.getJahr();

            // create new issue docstruct if necessary
            if (!lastIssue.equals(element.getBookletid())){
                lastIssue = element.getBookletid();
                issue = ff.getDigitalDocument().createDocStruct(issueType);

                // add title to issue
                addMetadata(issue, "TitleDocMain", title + " " + year + issueForTitle);
                // add publication year
                addMetadata(issue, "PublicationYear", year);
                // add publication date
                addMetadata(issue, "DateOfPublication", year + "-" + issueForDate + "-01");
                // add digital collection
                addMetadata(issue, "singleDigCollection", "ZS_RegioBodensee");
                // add viewer sub theme
                addMetadata(issue, "ViewerSubTheme", "bsz-st-bodenseebibliotheken");
                // add issue to volume
                volume.addChild(issue);
                if (createIssues){
                    adaptIdentifier(issue, "CatalogIDDigital", ppn_volume);
                    ff.getDigitalDocument().getLogicalDocStruct().addChild(issue);
                }
            }

            // copy each image into right place in tmp folder
            File imageFile = new File(bsz_import_folder, element.getJpg().substring(image_file_prefix_to_remove.length() -1));
            log.debug("copy image from " + imageFile.getAbsolutePath() + " to " + targetFolderImages.getAbsolutePath());
            FileUtils.copyFile(imageFile, new File(targetFolderImages, String.format("%08d", physicalPageNumber) + image_file_suffiix_to_use));
            //FileUtils.copyFile(imageFile, new File(targetFolderImages, StringUtils.leftPad(issueNumber, 2, "0") + "_" + String.format("%08d", physicalPageNumber) + IMAGE_FILE_SUFFIX_TO_USE));

            // no matter if new or current issue, add now all pages to current issue
            if (issue!=null){

                // assigning page numbers
                DocStructType newPage = prefs.getDocStrctTypeByName("page");
                DocStruct dsPage = ff.getDigitalDocument().createDocStruct(newPage);
                physicaldocstruct.addChild(dsPage);
                addMetadata(dsPage, "physPageNumber", String.valueOf(physicalPageNumber++));
                addMetadata(dsPage, "logicalPageNumber", element.getLabel());
                volume.addReferenceTo(dsPage, "logical_physical");
                if (createIssues){
                    issue.addReferenceTo(dsPage, "logical_physical");
                }

                // image name
                ContentFile cf = new ContentFile();
                if (SystemUtils.IS_OS_WINDOWS) {
                    cf.setLocation("file:/" + inProcessTitle + image_folder_extension + imageFile.getName());
                } else {
                    cf.setLocation("file://" + inProcessTitle + image_folder_extension + imageFile.getName());
                }
                dsPage.addContentFile(cf);
            }
        }
    }

    /**
     * Method to read all BSZ elements from the given JSON file
     * 
     * @param inYear The year to search for in the JSON file to just use elements from this year
     * @return ordered list of elements
     * 
     * @throws IOException
     */
    private List<BSZ_BodenseeImport_Element> readElementsFromSql(String inYearAndIssue) throws IOException{
        List<BSZ_BodenseeImport_Element> elements = new ArrayList<>();
        // read all elements from the sql file
        try {
            File f = new File (bsz_import_sql_file);
            List<String> lines = FileUtils.readLines(f, "UTF-8");
            for (String line : lines) {
                if (line.startsWith("INSERT INTO")){
                    BSZ_BodenseeImport_Element element = convertSqlToElement(line);

                    String isn = "";
                    if (StringUtils.isNumeric(element.getIssueNumber())){
                        isn = "_" + element.getIssueNumber();
                    }

                    String comparer = element.getJahr() + isn;
                    if (separateBookletIds){
                        comparer += "_" + element.getBookletid();
                    }
                    if (comparer.equals(inYearAndIssue)){
                        elements.add(element);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Problem occured while reading the sql file for " + basic_name + " import", e);
        }

        // sort all elements in the list first by order number
        Collections.sort(elements);
        return elements;
    }

    /**
     * Method to create or to adapt an identifier for a given structural element. The identifier that is
     * named as parameter 'field' is searched in the structural element and gets the prefix 'bsz' added if
     * it is not there already. In case the identifier is not existing add it now to the structural element
     * incl. the prefix 'bsz'
     * 
     * @param ds the structural element to use
     * @param field the metadata field that is added or adapted
     * @param value the metadata value (the identifier) to use in case it is still missing
     * 
     * @throws MetadataTypeNotAllowedException
     */
    private void adaptIdentifier(DocStruct ds, String field, String value) throws MetadataTypeNotAllowedException{
        List<? extends Metadata> mdlist = ds.getAllMetadataByType(prefs.getMetadataTypeByName(field));
        if (mdlist != null && mdlist.size()>0){
            Metadata md = mdlist.get(0);
            if (md!=null &&  !md.getValue().startsWith("bsz")){
                md.setValue("bsz" +  md.getValue());
            }
        }else {
            Metadata md2 = new Metadata(prefs.getMetadataTypeByName(field));
            md2.setValue("bsz" + value);
            ds.addMetadata(md2);
        }
    }

    /**
     * Method to add a specific metadata to a given structural element
     * 
     * @param ds structural element to use
     * @param field the metadata field to create
     * @param value the information the shall be stored as metadata in the given field
     * 
     * @throws MetadataTypeNotAllowedException
     */
    private void addMetadata(DocStruct ds, String field, String value) throws MetadataTypeNotAllowedException{
        Metadata mdColl = new Metadata(prefs.getMetadataTypeByName(field));
        mdColl.setValue(value);
        ds.addMetadata(mdColl);
    }
}
