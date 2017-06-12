package de.intranda.goobi.plugins.bsz;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IOpacPlugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.NIOFileUtils;
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

	private String image_folder_extension = "_" + ConfigurationHelper.getInstance().getMediaDirectorySuffix();
	private String image_file_prefix_to_remove;
	private String image_file_suffiix_to_use = ".jpg";
	
	//SQL converted to JSON simply using http://codebeautify.org/sql-to-json-converter
	private String bsz_import_json_file;
	private String bsz_import_folder;
	private String ppn_volume;
	private Prefs prefs;
	private String ppn;
	private String tempFolder;
	private String title;
	@Setter private boolean createIssues = true;
	
	public BSZ_BodenseeImport_Helper(String inBasicName){
		basic_name = inBasicName;
		image_file_prefix_to_remove = "/data/kebweb/" + basic_name + "/";
		bsz_import_json_file = basic_folder + basic_name + ".json";
		bsz_import_folder = basic_folder + basic_name + "/";
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
		// read json file to list all years
		LinkedHashSet<String> years = new LinkedHashSet<String>();
		try {
			JsonReader reader = new JsonReader(new FileReader(bsz_import_json_file));
			Gson gson = new GsonBuilder().create();
			reader.beginArray();
			while (reader.hasNext()) {
				BSZ_BodenseeImport_Element element = gson.fromJson(reader, BSZ_BodenseeImport_Element.class);
				years.add(element.getJahr());
			}
			reader.close();
		} catch (IOException e) {
			log.error("Problem occured while reading the json file for " + basic_name + " import", e);
		}
		ArrayList<String> mylist = new ArrayList<String>(years);
		Collections.sort(mylist);
		return mylist;
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
				} catch (COSVisitorException e) {
					log.error("COSVisitorException during the PDF conversion in massimport " + this.getClass().getName(), e);
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
//			if (myRdf != null) {
//				try {
//					ats = myImportOpac.createAtstsl(myRdf.getDigitalDocument().getLogicalDocStruct()
//					        .getAllMetadataByType(prefs.getMetadataTypeByName("TitleDocMain")).get(0).getValue(), null)
//					        .toLowerCase();
//				} catch (Exception e) {
//					ats = "";
//				}
//			}
			
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
	private void extractPdf(String inYear, String inProcessTitle) throws IOException, COSVisitorException{
		File targetFolderPdfSingles = new File(tempFolder + ppn_volume + File.separator + "ocr" 
				+ File.separator + inProcessTitle + "_pdf");
		targetFolderPdfSingles.mkdirs();
		// copy pdf files into right place in tmp folder
		int pdfCounter = 1;
		
		List<Path> allMyFiles = NIOFileUtils.listFiles(bsz_import_folder);
		for (Path entry : allMyFiles) {
			File f = entry.toFile();
			if (f.getName().endsWith(".pdf") && f.getName().contains("_j" + inYear + "_")) {
				// create single page pdf files
				PDDocument inputDocument = PDDocument.loadNonSeq(f, null);
				for (int page = 1; page <= inputDocument.getNumberOfPages(); ++page) {
					PDPage pdPage = (PDPage) inputDocument.getDocumentCatalog().getAllPages().get(page - 1);
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
	
	/**
	 * Method to add all issues into the the generated {@link Fileformat} incl. page labels, structural sub elements
	 * and page assignements. In case a pdf file exists for a the volume too it is extracted into individual pages
	 * 
	 * @param ff {@link Fileformat} to use for the enrichtment
	 * @param inYear the year to use for the extraction of data from the JSON result
	 * @param inProcessTitle the process title to use for copying the final results at the end
	 * 
	 * @throws IOException
	 * @throws UGHException
	 * @throws COSVisitorException
	 */
	private void addAllIssues(Fileformat ff, String inYear, String inProcessTitle) throws IOException, UGHException, COSVisitorException {
		File targetFolderImages = new File(tempFolder + ppn_volume + File.separator + "images" 
        		+ File.separator + inProcessTitle + image_folder_extension);
		targetFolderImages.mkdirs();
		
		// extract given pdf file
		extractPdf(inYear,inProcessTitle);
		
		DocStruct physicaldocstruct = ff.getDigitalDocument().getPhysicalDocStruct();
		DocStruct volume = ff.getDigitalDocument().getLogicalDocStruct().getAllChildren().get(0);
		DocStructType issueType = prefs.getDocStrctTypeByName("PeriodicalIssue");
		DocStruct issue = null;
		String lastIssue = "";
		int physicalPageNumber = 1;

		// add title to volume
		addMetadata(volume, "TitleDocMain", title + " " + inYear);
		// add current number
		addMetadata(volume, "CurrentNo", inYear);	
		// add current number sorting
		addMetadata(volume, "CurrentNoSorting", inYear);		
		// add viewer theme
		addMetadata(volume, "ViewerSubTheme", "bsz-st-bodenseebibliotheken");	
		// add collections
		addMetadata(volume, "singleDigCollection", "ZS_RegioBodensee");	
		// add publication year
		addMetadata(volume, "PublicationYear", inYear);	
		
		DocStruct logicalDocstruct = ff.getDigitalDocument().getLogicalDocStruct();
        if (logicalDocstruct.getType().isAnchor()) {
            if (logicalDocstruct.getAllChildren() != null && logicalDocstruct.getAllChildren().size() > 0) {
            	logicalDocstruct = logicalDocstruct.getAllChildren().get(0);
            }
        }
		
		// run through all JSON elements to start the import now and add all issues and pages from one year to this volume
        List<BSZ_BodenseeImport_Element> elements = readElementsFromJson(inYear);
		for (BSZ_BodenseeImport_Element element : elements) {

			String issueNumber = element.getIssueNumber();

			// create new issue docstruct if necessary
			if (!lastIssue.equals(element.getBookletid())){
				lastIssue = element.getBookletid();
				issue = ff.getDigitalDocument().createDocStruct(issueType);
				
				// add title to issue
				addMetadata(issue, "TitleDocMain", "Heft " + issueNumber + " / " + inYear);	
				// add publication year
				addMetadata(issue, "PublicationYear", inYear);
				// add publication date
				addMetadata(issue, "DateOfPublication", inYear + "-" + issueNumber + "-01");
				// add issue to volume
				if (createIssues){
					volume.addChild(issue);
				}
			}
			
			// copy each image into right place in tmp folder
			File imageFile = new File(bsz_import_folder, element.getJpg().substring(image_file_prefix_to_remove.length() -1));
			log.info("copy image from " + imageFile.getAbsolutePath() + " to " + targetFolderImages.getAbsolutePath());
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
	private List<BSZ_BodenseeImport_Element> readElementsFromJson(String inYear) throws IOException{
        // read all elements from the JSON file
        List<BSZ_BodenseeImport_Element> elements = new ArrayList<BSZ_BodenseeImport_Element>();
		JsonReader reader = new JsonReader(new FileReader(bsz_import_json_file));
		Gson gson = new GsonBuilder().create();
		reader.beginArray();
		while (reader.hasNext()) {
			BSZ_BodenseeImport_Element element = gson.fromJson(reader, BSZ_BodenseeImport_Element.class);
			if (element.getJahr().equals(inYear)){
				elements.add(element);
			}
		}
		reader.close();
		
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
