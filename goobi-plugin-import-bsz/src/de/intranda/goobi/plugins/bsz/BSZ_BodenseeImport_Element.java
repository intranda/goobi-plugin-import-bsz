package de.intranda.goobi.plugins.bsz;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Data;

@Data
public class BSZ_BodenseeImport_Element implements Comparable<BSZ_BodenseeImport_Element>{
	private String pageid;
	private String bookletid;
	private String journalid;
	private String lfnr;
	private String jahr;
	private String label;
	private String jpg;
	
	public int compareTo(BSZ_BodenseeImport_Element compareElement) {
		return jpg.compareTo(compareElement.getJpg());
	}
	
	public String getIssueNumber(){
		if (pageid.startsWith("aaaaaaaaa")){
			String nr = bookletid.substring(bookletid.lastIndexOf(".") + 1);
			return nr;
		}else{
			String nr = pageid.replaceAll("_", "-");
			nr = nr.substring(nr.indexOf("-h") + 2);
			Matcher matcher = Pattern.compile("-[a-z]", Pattern.CASE_INSENSITIVE).matcher(nr);        
			if (matcher.find()) {
				nr = nr.substring(0,matcher.start());
			}
			if (nr.length()<2){
				nr = "0" + nr;
			}
			return nr;
		}
	}
}
