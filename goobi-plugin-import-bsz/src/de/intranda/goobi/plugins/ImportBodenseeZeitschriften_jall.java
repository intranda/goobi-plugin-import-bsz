package de.intranda.goobi.plugins;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.goobi.production.enums.ImportType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.importer.DocstructElement;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.interfaces.IImportPluginVersion2;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.properties.ImportProperty;

import de.intranda.goobi.plugins.bsz.BSZ_BodenseeImport_Helper;
import de.sub.goobi.forms.MassImportForm;
import de.sub.goobi.helper.exceptions.ImportPluginException;
import lombok.extern.log4j.Log4j;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;

@PluginImplementation
@Log4j
public class ImportBodenseeZeitschriften_jall implements IImportPluginVersion2, IPlugin {
	
	private static final String BASIC_NAME = "jall";
	private static final String ppn = "408063912";
	private static final String title = "Jahrbuch des Landkreises Lindau";
	private static final boolean createIssues = false;
	private static final String PLUGIN_NAME = "ImportBodenseeZeitschriften_" + BASIC_NAME;
	private BSZ_BodenseeImport_Helper bszHelper = new BSZ_BodenseeImport_Helper(BASIC_NAME);
	
	private MassImportForm form;
	private Prefs prefs;
	private String importFolder = "";
	
	// after the import this command has to be called for the conversion of single page pdfs into alto files (out of Goobi)
	// /usr/bin/java -jar {scriptsFolder}Pdf2Alto.jar {processpath}/ocr/{processtitle}_pdf/ {tifpath} {processpath}/ocr/{processtitle}_alto/
	
	@Override
	public List<String> getAllFilenames() {
		return bszHelper.getYearsFromJson();
	}
	
	@Override
	public List<ImportObject> generateFiles(List<Record> records) {
		bszHelper.prepare(prefs, ppn, importFolder, title);
		bszHelper.setCreateIssues(createIssues);
		
		List<ImportObject> answer = new ArrayList<ImportObject>();
		
		// run through all selected records and start to prepare the import
		for (Record record : records) {
			// updatae the progress bar for each record
			form.addProcessToProgressBar();
			bszHelper.generateImportObject(answer, record);
		}
		return answer;
	}
	
	@Override
    public Fileformat convertData() throws ImportPluginException {
		return null;
	}
	
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
		return null;
	}

	@Override
	public void setForm(MassImportForm form) {
		this.form = form;

	}

	@Override
	public boolean isRunnableAsGoobiScript() {
		return true;
	}

	
}
