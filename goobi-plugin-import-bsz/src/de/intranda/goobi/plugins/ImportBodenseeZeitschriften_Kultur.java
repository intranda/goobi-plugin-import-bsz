package de.intranda.goobi.plugins;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.PluginLoader;
import org.goobi.production.plugin.interfaces.IImportPlugin;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import de.intranda.goobi.plugins.bsz.BSZ_Kultur_Element;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.ContentFile;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@PluginImplementation
@Log4j
public class ImportBodenseeZeitschriften_Kultur implements IImportPlugin, IPlugin {
	private static final String PLUGIN_NAME = "ImportBodenseeZeitschriften_Kultur";

	private static final String IMPORT_FOLDER = "/opt/digiverso/goobi/metadata/25810/images/kult/";
//	private static final String IMPORT_FOLDER = "/Users/steffen/Desktop/BSZ/";
	private static final String IMAGE_FOLDER_EXTENSION = "_" + ConfigurationHelper.getInstance().getMediaDirectorySuffix();
	private static final String IMAGE_FILE_PREFIX_TO_REMOVE = "/data/kebweb/kult/";
	private static final String IMAGE_FILE_SUFFIX_TO_USE = ".jpg";
	//SQL converted to JSON simply using http://codebeautify.org/sql-to-json-converter
	private static final String IMPORT_FILE = IMPORT_FOLDER + "import_kultur.json";
	
	// after the import this command has to be called for the conversion of single page pdfs into alto files (out of Goobi)
	// /usr/bin/java -jar {scriptsFolder}Pdf2Alto.jar {processpath}/ocr/{processtitle}_pdf/ {tifpath} {processpath}/ocr/{processtitle}_alto/
	
	private MassImportForm form;
	private Prefs prefs;
	private String data = "";
	private String importFolder = "";
	private String ats = "kultur";
	private String catalogue = "BSZ-BW";
	private String ppn = "310869048";
	private String ppn_volume;
	
	@Override
	public List<String> getAllFilenames() {
		// read json file to list all years
		LinkedHashSet<String> years = new LinkedHashSet<String>();
		try {
			JsonReader reader = new JsonReader(new FileReader(IMPORT_FILE));
			Gson gson = new GsonBuilder().create();
			reader.beginArray();
			while (reader.hasNext()) {
				BSZ_Kultur_Element element = gson.fromJson(reader, BSZ_Kultur_Element.class);
				years.add(element.getJahr());
			}
			reader.close();
		} catch (IOException e) {
			log.error("Problem occured while reading the json file for kultur import", e);
		}
		return new ArrayList<String>(years);
	}
	
	@Override
	public List<ImportObject> generateFiles(List<Record> records) {
		List<ImportObject> answer = new ArrayList<ImportObject>();
		
		// run through all selected records and start to prepare the import
		for (Record record : records) {
			// updatae the progress bar for each record
			form.addProcessToProgressBar();
			
			// generate an ImportObject for each selected year and add it to the result list later on
			ImportObject importObjectYear = new ImportObject();
			ppn_volume = ppn + "_" + record.getId();
			String metsFileName = getImportFolder() + ppn_volume + ".xml";
			importObjectYear.setMetsFilename(metsFileName);
			importObjectYear.setProcessTitle("kultur_" + ppn_volume);
			try {
				// request the object from the catalogue and generate a FileFormat
				Fileformat fileformat = convertData();
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
						log.error("Errow while doing the massimport in ImportBodenseeZeitschriften_Kultur", e1);
					}
					
					try {
						// add all issues and the correct pages there
						addAllIssues(fileformat, record.getId(),importObjectYear.getProcessTitle());
						
						// write Mets file into temp folder of Goobi to let it be imported afterwards
						MetsMods mm = new MetsMods(this.prefs);
						mm.setDigitalDocument(fileformat.getDigitalDocument());
						log.debug("Writing '" + metsFileName + "' into given folder...");
						mm.write(metsFileName);
						
						importObjectYear.setImportReturnValue(ImportReturnValue.ExportFinished);
					} catch (IOException e) {
						log.error("IOException during the massimport ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
					} catch (WriteException e) {
						log.error("WriteException during the massimport ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.WriteError);
					} catch (UGHException e) {
						log.error("PreferencesException during the massimport ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
					} catch (COSVisitorException e) {
						log.error("COSVisitorException during the PDF conversion in massimport ImportBodenseeZeitschriften_Kultur", e);
						importObjectYear.setErrorMessage(e.getMessage());
						importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
					}
				} else {
					importObjectYear.setImportReturnValue(ImportReturnValue.InvalidData);
				}
				
				answer.add(importObjectYear);
			} catch (ImportPluginException e) {
				log.error("ImportPluginException during the massimport in ImportBodenseeZeitschriften_Kultur", e);
			}
		}
		return answer;
	}

	
	/**
	 * add all issues and pages to the volume
	 * @param ff
	 */
	private void addAllIssues(Fileformat ff, String inYear, String inProcessTitle) throws IOException, UGHException, COSVisitorException{
		File targetFolderImages = new File(getImportFolder() + ppn_volume + File.separator + "images" 
        		+ File.separator + inProcessTitle + IMAGE_FOLDER_EXTENSION);
		File targetFolderPdfSingles = new File(getImportFolder() + ppn_volume + File.separator + "ocr" 
				+ File.separator + inProcessTitle + "_pdf");
        targetFolderImages.mkdirs();
		targetFolderPdfSingles.mkdirs();
		
		DocStruct physicaldocstruct = ff.getDigitalDocument().getPhysicalDocStruct();
		DocStruct volume = ff.getDigitalDocument().getLogicalDocStruct().getAllChildren().get(0);
		DocStructType issueType = prefs.getDocStrctTypeByName("PeriodicalIssue");
		DocStruct issue = null;
		String lastIssue = "";
		int physicalPageNumber = 1;

		// add title to volume
		Metadata mdVolTitle = new Metadata(prefs.getMetadataTypeByName("TitleDocMain"));
		mdVolTitle.setValue("Zeitschrift für Kultur und Gesellschaft " + inYear);
		volume.addMetadata(mdVolTitle);
		// add current number
		Metadata mdVolNum = new Metadata(prefs.getMetadataTypeByName("CurrentNo"));
		mdVolNum.setValue(inYear);
		volume.addMetadata(mdVolNum);
		// add current number sorting
		Metadata mdVolNumSort = new Metadata(prefs.getMetadataTypeByName("CurrentNoSorting"));
		mdVolNumSort.setValue(inYear);
		volume.addMetadata(mdVolNumSort);
		// add viewer theme
		Metadata mdViewer = new Metadata(prefs.getMetadataTypeByName("ViewerSubTheme"));
		mdViewer.setValue("bsz-st-bodenseebibliotheken");
		volume.addMetadata(mdViewer);
		// add collections
		Metadata mdColl = new Metadata(prefs.getMetadataTypeByName("singleDigCollection"));
		mdColl.setValue("ZS_RegioBodensee");
		volume.addMetadata(mdColl);
		// add publication year
		Metadata mdVolYear = new Metadata(prefs.getMetadataTypeByName("PublicationYear"));
		mdVolYear.setValue(inYear);
		volume.addMetadata(mdVolYear);
		
		// copy pdf files into right place in tmp folder
		int pdfCounter = 1;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(IMPORT_FOLDER))) {
			for (Path entry : stream) {
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
		}catch (Exception e) {
			log.error("Error listing all folders in import root", e);
		}
		
		DocStruct logicalDocstruct = ff.getDigitalDocument().getLogicalDocStruct();
        if (logicalDocstruct.getType().isAnchor()) {
            if (logicalDocstruct.getAllChildren() != null && logicalDocstruct.getAllChildren().size() > 0) {
            	logicalDocstruct = logicalDocstruct.getAllChildren().get(0);
            }
        }
		
		JsonReader reader = new JsonReader(new FileReader(IMPORT_FILE));
		Gson gson = new GsonBuilder().create();
		reader.beginArray();
		while (reader.hasNext()) {
			BSZ_Kultur_Element element = gson.fromJson(reader, BSZ_Kultur_Element.class);
			// add all issues and pages from one year to this volume
			if (element.getJahr().equals(inYear)){
				
				String issueNumber = element.getBookletid().substring(element.getBookletid().lastIndexOf(".") + 1);

				// create new issue docstruct if necessary
				if (!lastIssue.equals(element.getBookletid())){
					lastIssue = element.getBookletid();
					issue = ff.getDigitalDocument().createDocStruct(issueType);
					
					// add title to issue
					Metadata mdIssueTitle = new Metadata(prefs.getMetadataTypeByName("TitleDocMain"));
					mdIssueTitle.setValue("Heft " + issueNumber + " / " + inYear);
					issue.addMetadata(mdIssueTitle);
					// add publication year
					Metadata mdYear = new Metadata(prefs.getMetadataTypeByName("PublicationYear"));
					mdYear.setValue(inYear);
					issue.addMetadata(mdYear);
					// add publication date
					Metadata mdDate = new Metadata(prefs.getMetadataTypeByName("DateOfPublication"));
					mdDate.setValue(inYear + issueNumber + "01");
					issue.addMetadata(mdDate);
					
					volume.addChild(issue);
				}
				
				// copy each image into right place in tmp folder
				File imageFile = new File(IMPORT_FOLDER, element.getJpg().substring(IMAGE_FILE_PREFIX_TO_REMOVE.length() -1));
				log.info("copy image from " + imageFile.getAbsolutePath() + " to " + targetFolderImages.getAbsolutePath());
				FileUtils.copyFile(imageFile, new File(targetFolderImages, String.format("%08d", physicalPageNumber) + IMAGE_FILE_SUFFIX_TO_USE));
				//FileUtils.copyFile(imageFile, new File(targetFolderImages, StringUtils.leftPad(issueNumber, 2, "0") + "_" + String.format("%08d", physicalPageNumber) + IMAGE_FILE_SUFFIX_TO_USE));
				
				// no matter if new or current issue, add now all pages to current issue
				if (issue!=null){
					
					DocStructType newPage = prefs.getDocStrctTypeByName("page");
					DocStruct dsPage = ff.getDigitalDocument().createDocStruct(newPage);

					// physical page number
					physicaldocstruct.addChild(dsPage);
					MetadataType mdt = prefs.getMetadataTypeByName("physPageNumber");
					Metadata mdTemp = new Metadata(mdt);
					mdTemp.setValue(String.valueOf(physicalPageNumber++));
					dsPage.addMetadata(mdTemp);

					// logical page number
					mdt = prefs.getMetadataTypeByName("logicalPageNumber");
					mdTemp = new Metadata(mdt);
					mdTemp.setValue(element.getLabel());

					// assign page to topscruct
					dsPage.addMetadata(mdTemp);
					volume.addReferenceTo(dsPage, "logical_physical");
					issue.addReferenceTo(dsPage, "logical_physical");
					
					// image name
					ContentFile cf = new ContentFile();
					if (SystemUtils.IS_OS_WINDOWS) {
						cf.setLocation("file:/" + inProcessTitle + IMAGE_FOLDER_EXTENSION + imageFile.getName());
					} else {
						cf.setLocation("file://" + inProcessTitle + IMAGE_FOLDER_EXTENSION + imageFile.getName());
					}
					dsPage.addContentFile(cf);
					
				}
			}
		}
		reader.close();
	}
	
	@Override
    public Fileformat convertData() throws ImportPluginException {
		try {
			ConfigOpacCatalogue coc = ConfigOpac.getInstance().getCatalogueByName(catalogue);
			IOpacPlugin myImportOpac = (IOpacPlugin) PluginLoader.getPluginByTitle(PluginType.Opac, coc.getOpacType());
			Fileformat myRdf = myImportOpac.search("12", ppn, coc, prefs);
			if (myRdf != null) {
				try {
					ats = myImportOpac.createAtstsl(myRdf.getDigitalDocument().getLogicalDocStruct()
					        .getAllMetadataByType(prefs.getMetadataTypeByName("TitleDocMain")).get(0).getValue(), null)
					        .toLowerCase();
				} catch (Exception e) {
					ats = "";
				}
			}
			
			// assign a ppn digital to the anchor docstruct
			DocStruct ds = myRdf.getDigitalDocument().getLogicalDocStruct();
			Metadata md = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
			md.setValue(ppn);
			ds.addMetadata(md);
			// add viewer theme
			Metadata mdViewer = new Metadata(prefs.getMetadataTypeByName("ViewerSubTheme"));
			mdViewer.setValue("bsz-st-bodenseebibliotheken");
			ds.addMetadata(mdViewer);
			// add collections
			Metadata mdColl = new Metadata(prefs.getMetadataTypeByName("singleDigCollection"));
			mdColl.setValue("ZS_RegioBodensee");
			ds.addMetadata(mdColl);
			
			
			// assign a ppn digital to the child docstruct (volume)
			if (ds.getType().isAnchor()) {
				DocStruct child = ds.getAllChildren().get(0);
				Metadata md2 = new Metadata(prefs.getMetadataTypeByName("CatalogIDDigital"));
				md2.setValue(ppn_volume);
				child.addMetadata(md2);
			}
			
			return myRdf;
		} catch (Exception e) {
			log.error("Exception while creating the FileFormat", e);
		}
		return null;
	}
	
//	private List<BSZ_Kultur_Element> getKulturElements(){
//		Gson gson = new Gson();
//		BufferedReader br = new BufferedReader(new FileReader(IMPORT_FILE));
////		Type type = new TypeToken<List<Model>>(){}.getType();
//		List<BSZ_Kultur_Element> elements = gson.fromJson(br, List<BSZ_Kultur_Element>);
//		
//		
////		try(Reader reader = new InputStreamReader(JsonToJava.class.getResourceAsStream("/Server1.json"), "UTF-8")){
////            
////        	Gson gson = new GsonBuilder().create();
////            Person p = gson.fromJson(reader, Person.class);
////            System.out.println(p);
////        }
//	}
	
	


	@Override
	public List<Record> generateRecordsFromFilenames(List<String> filenames) {
		List<Record> records = new ArrayList<Record>();
		for (String filename : filenames) {
			Record rec = new Record();
			rec.setData(filename);
			rec.setId(filename);
			records.add(rec);
		}
		return records;
	}
	
	@Override
	public void deleteFiles(List<String> selectedFilenames) {
		// for (String filename : selectedFilenames) {
		// File f = new File(ROOT_FOLDER, filename);
		// // TODO delete
		// logger.debug("Delete folder " + f.getAbsolutePath());
		// }
	}
	
	
	@Override
	public void setImportFolder(String folder) {
		this.importFolder = folder;
	}

	@Override
	public List<Record> splitRecords(String records) {
		return null;
	}

	@Override
	public List<Record> generateRecordsFromFile() {
		return null;
	}

	@Override
	public PluginType getType() {
		return PluginType.Import;
	}

	@Override
	public String getTitle() {
		return PLUGIN_NAME;
	}

	@Override
	public void setPrefs(Prefs prefs) {
		this.prefs = prefs;
	}

	@Override
	public void setData(Record r) {
		data = r.getData();
	}

	@Override
	public String getImportFolder() {
		return this.importFolder;
	}

	@Override
	public void setFile(File importFile) {
	}

	@Override
	public List<String> splitIds(String ids) {
		return null;
	}

	@Override
	public List<ImportType> getImportTypes() {
		List<ImportType> typeList = new ArrayList<ImportType>();
		typeList.add(ImportType.FOLDER);
		return typeList;
	}

	@Override
	public List<ImportProperty> getProperties() {
		return null;
	}

	@Override
	public List<? extends DocstructElement> getCurrentDocStructs() {
		return null;
	}

	@Override
	public String deleteDocstruct() {
		return null;
	}

	@Override
	public String addDocstruct() {
		return null;
	}

	@Override
	public List<String> getPossibleDocstructs() {
		return null;
	}

	@Override
	public DocstructElement getDocstruct() {
		return null;
	}

	@Override
	public void setDocstruct(DocstructElement dse) {
	}

	@Override
	public String getProcessTitle() {
		return ats;
	}
	
	// public static void main(String[] args) {
	//
	// String path = "/home/robert/Downloads/bsz/007140975";
	//
	// List<String> values = validate(path);
	// if (!values.isEmpty()) {
	// for (String message : values) {
	// logger.error(message);
	// }
	// } else {
	// logger.debug("Folder " + path + " is valid.");
	// }
	// ImageNameImportPlugin plugin = new ImageNameImportPlugin();
	// for (String filename : plugin.getAllFilenames()) {
	// System.out.println(filename);
	// }
	//
	// }

	@Override
	public void setForm(MassImportForm form) {
		this.form = form;

	}
}
