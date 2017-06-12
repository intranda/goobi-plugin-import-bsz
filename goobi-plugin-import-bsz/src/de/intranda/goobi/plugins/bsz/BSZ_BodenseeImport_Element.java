package de.intranda.goobi.plugins.bsz;

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
//		int nr1 = Integer.parseInt(lfnr);
//		int nr2 = Integer.parseInt(compareElement.lfnr);
//		return nr2 - nr1;

		return jpg.compareTo(compareElement.getJpg());
	}
	
	public String getIssueNumber(){
		String nr = bookletid.substring(bookletid.lastIndexOf(".") + 1);
		return nr;
	}
}
