/*
    ***** BEGIN LICENSE BLOCK *****
	
	Copyright (c) 2009  Zotero
	                    Center for History and New Media
						George Mason University, Fairfax, Virginia, USA
						http://zotero.org
	
	This program is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.
	
	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.
	
	You should have received a copy of the GNU General Public License
	along with this program.  If not, see <http://www.gnu.org/licenses/>.
    
    ***** END LICENSE BLOCK *****
*/

package org.zotero.integration.ooo;

import java.io.UnsupportedEncodingException;

import com.sun.star.beans.PropertyValue;
import com.sun.star.beans.XMultiPropertyStates;
import com.sun.star.beans.XPropertySet;
import com.sun.star.container.XNamed;
import com.sun.star.document.XDocumentInsertable;
import com.sun.star.frame.XDispatchHelper;
import com.sun.star.frame.XDispatchProvider;
import com.sun.star.lang.IllegalArgumentException;
import com.sun.star.lang.XComponent;
import com.sun.star.lang.XServiceInfo;
import com.sun.star.text.ControlCharacter;
import com.sun.star.text.XFootnote;
import com.sun.star.text.XSimpleText;
import com.sun.star.text.XText;
import com.sun.star.text.XTextContent;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextRange;
import com.sun.star.text.XTextRangeCompare;
import com.sun.star.uno.UnoRuntime;

public class ReferenceMark implements Comparable<ReferenceMark> {
	// NOTE: This list must be sorted. See the API docs for XMultiPropertySet for more details.
	private static final String[] PROPERTIES_CHANGE_TO_DEFAULT =
		{"CharCaseMap", "CharEscapement", "CharEscapementHeight", "CharPosture", "CharUnderline", "CharWeight"};
	
	protected Document doc;
	protected XTextRangeCompare textRangeCompare;
	protected XTextContent textContent;
	public XTextRange range;
	protected XText text;
	protected XNamed named;
	protected boolean isNote;
	protected boolean isTextSection;
	protected boolean isDisposable;
	public String rawCode;
	
	public ReferenceMark(Document aDoc, Object aMark) {
		doc = aDoc;
		textContent = (XTextContent) UnoRuntime.queryInterface(XTextContent.class, aMark);
		range = textContent.getAnchor();
		text = range.getText();
		named = ((XNamed) UnoRuntime.queryInterface(XNamed.class, aMark));
		
		XServiceInfo serviceInfo = (XServiceInfo) UnoRuntime.queryInterface(XServiceInfo.class, text);
		isNote = serviceInfo.supportsService("com.sun.star.text.Footnote");
		serviceInfo = (XServiceInfo) UnoRuntime.queryInterface(XServiceInfo.class, aMark);
		isTextSection = serviceInfo.supportsService("com.sun.star.text.TextSection");
		isDisposable = isTextSection;
		
		textRangeCompare = (XTextRangeCompare) UnoRuntime.queryInterface(XTextRangeCompare.class, text);
		
		rawCode = named.getName();
	}
	
	public void delete() {
		try {
			if(isWholeRange()) {
				((XComponent) UnoRuntime.queryInterface(XComponent.class, text)).dispose();
			} else {		
				// delete mark
				range.setString("");
				// dispose of a Bookmark or TextSection
				if(isDisposable) {
					((XComponent) UnoRuntime.queryInterface(XComponent.class, textContent)).dispose();
				}
			}
		} catch(Exception e) {
			doc.displayAlert(Document.getErrorString(e), 0, 0);
		}
	}
	
	public void removeCode() {
		try {
			if(isDisposable) {
				((XComponent) UnoRuntime.queryInterface(XComponent.class, textContent)).dispose();
			} else {
				// TODO: won't work with formatted text
				range.setString(range.getString());
			}
		} catch(Exception e) {
			doc.displayAlert(Document.getErrorString(e), 0, 0);
		}
	}
	
	public void select() {
		try {
			XTextCursor cursor = doc.getSelection();
			cursor.gotoRange(range, false);
			if(isTextSection) {
				cursor.goLeft((short) 1, true);
			}
		} catch(Exception e) {
			doc.displayAlert(Document.getErrorString(e), 0, 0);
		}
	}
	
	public void setText(String textString, boolean isRich) {
		Object[] oldPropertyValues;
		XPropertySet rangeProps;
		
		try {
			boolean multiline = isRich && (textString.contains("\\\n")	|| textString.contains("\\par") || textString.contains("\\\r\n"));
			
			if(multiline) {
				prepareMultiline();
			}
			
			XTextCursor cursor = text.createTextCursorByRange(range);
			range = cursor;
			
			if(!multiline) {
				oldPropertyValues = new Object[PROPERTIES_CHANGE_TO_DEFAULT.length];
				
				// store properties
				rangeProps = (XPropertySet) UnoRuntime.queryInterface(XPropertySet.class, range);
				for(int i=0; i<PROPERTIES_CHANGE_TO_DEFAULT.length; i++) {
					oldPropertyValues[i] = rangeProps.getPropertyValue(PROPERTIES_CHANGE_TO_DEFAULT[i]);
				}
				
				// move citation to its own paragraph so its formatting isn't altered automatically
				// because of the text on either side of it
				int previousLen = range.getString().length();
				text.insertControlCharacter(range, ControlCharacter.PARAGRAPH_BREAK, false);
				text.insertControlCharacter(range.getEnd(), ControlCharacter.PARAGRAPH_BREAK, false);
				cursor.collapseToStart();
				moveCursorRight(cursor, previousLen);
			}

			XMultiPropertyStates rangePropStates = (XMultiPropertyStates) UnoRuntime.queryInterface(XMultiPropertyStates.class, cursor);
			rangePropStates.setPropertiesToDefault(PROPERTIES_CHANGE_TO_DEFAULT);

			if(isRich) {
				if(multiline) {
					// Add a new line to the start of the bibliography so that the paragraph format
					// for the first entry will be correct. Without the new line, when converting
					// citation styles, the first entry of the bibliography will keep the same paragraph
					// formatting as the previous citation style
					textString = "{\\rtf\\\n" + textString.substring(6);
				}

				insertRTF(textString, cursor);

				if(multiline) {
					// Remove the new line from the bibliography (added above). Have to remove the
					// new line before the textSection and then adjust the range so the new line
					// starting the textSection is outside of the range so that the 
					// paragraph formatting of the first entry remains unchanged. Also remove the
					//  extra new line at the end of the textSection.
					String rangeString = cursor.getString();
					int previousLen = rangeString.length();
					System.out.println(previousLen);
					int removeLastNewLine = 0;
					if(rangeString.codePointAt(previousLen-1) == 10) {
						removeLastNewLine = 1;
						XTextCursor dupRange = text.createTextCursorByRange(range);
						dupRange.collapseToEnd();
						dupRange.goLeft((short) 1, true);
						dupRange.setString("");
					}
					moveCursorRight(cursor, previousLen-removeLastNewLine);
				}
			} else {
				range.setString(textString);
			}

			reattachMark();
			
			if(!multiline) {
				// remove previously added paragraphs
				XTextCursor dupRange = text.createTextCursorByRange(range);
				dupRange.collapseToEnd();
				dupRange.goRight((short) 1, true);
				dupRange.setString("");

				getOutOfField();

				dupRange = text.createTextCursorByRange(range);
				dupRange.collapseToStart();
				dupRange.goLeft((short) 1, true);
				dupRange.setString("");
			}
		} catch(Exception e) {
			doc.displayAlert(Document.getErrorString(e), 0, 0);
		}
	}

	public void setCode(String code) {
		try {
			rawCode = Document.PREFIXES[0] + code + " RND" + Document.getRandomString(Document.REFMARK_ADD_CHARS);
			if(isTextSection) {
				named.setName(rawCode);
			} else {
				// The only way to rename a ReferenceMark is to delete it and add it again
				// TODO: won't work with formatted text
				range.setString(range.getString());
				reattachMark();
			}
		} catch(Exception e) {
			doc.displayAlert(Document.getErrorString(e), 0, 0);
		}
	}
	
	public String getCode() {
		try {
			int rnd = rawCode.lastIndexOf(" RND");
			if(rnd == -1) rnd = rawCode.length()-6;	// for compatibility with old, pre-release Python plug-in
			
			for(String prefix : Document.PREFIXES) {
				if(rawCode.startsWith(prefix)) {
					return rawCode.substring(prefix.length(), rnd);
				}
			}
			
			throw new Exception("Invalid code prefix");
		} catch(Exception e) {
	    	doc.displayAlert(Document.getErrorString(e), 0, 0);
	    	return null;
	    }
	}
	
	public Integer getNoteIndex() {
		try {
			// TODO: need to figure out how to get footnote index in OOo without massive cost
			return null;
		} catch(Exception e) {
	    	doc.displayAlert(Document.getErrorString(e), 0, 0);
	    	return null;
	    }
	}
	
	public boolean equals(ReferenceMark o) {
		return compareTo(o) == 0;
	}
	
	public int compareTo(ReferenceMark o) {
		XTextRange range1, range2;
		
		if(isNote) {
			range1 = ((XTextContent) UnoRuntime.queryInterface(XTextContent.class, text)).getAnchor();
		} else {
			range1 = range;
		}
		
		if(o.isNote) {
			range2 = ((XTextContent) UnoRuntime.queryInterface(XTextContent.class, o.text)).getAnchor();
		} else {
			range2 = o.range;
		}
		
		int cmp;
		try {
			cmp = doc.textRangeCompare.compareRegionStarts(range2, range1);
		} catch (IllegalArgumentException e) {
			return 0;
		}
		
		if(cmp == 0 && isNote && o.isNote) {
			try {
				cmp = textRangeCompare.compareRegionStarts(o.range, range);
			} catch (IllegalArgumentException e) {
				return 0;
			}
		}
		
		return cmp;
	}
	
	XTextCursor getReplacementCursor() throws Exception {
		if(isWholeRange()) {
			XFootnote footnote = (XFootnote) UnoRuntime.queryInterface(XFootnote.class, text);
			return doc.text.createTextCursorByRange(footnote.getAnchor());
		} else {
			XTextCursor cursor = text.createTextCursorByRange(range);
			if(isDisposable) {
				// dispose of text section
				((XComponent) UnoRuntime.queryInterface(XComponent.class, textContent)).dispose();
			}
			return cursor;
		}
	}
	
	protected void prepareMultiline() throws Exception {
		if(!isTextSection) {	// need to convert to TextSection
			range.setString("");

			// add a paragraph before creating multiline field at end of document
			// if this is not done, it's hard to add text after the TextSection
			if(doc.textRangeCompare.compareRegionEnds(doc.text.getEnd(), range.getEnd()) == 0) {
				doc.text.insertControlCharacter(doc.text.getEnd(), ControlCharacter.PARAGRAPH_BREAK, false);
				doc.text.insertControlCharacter(doc.text.getEnd(), ControlCharacter.PARAGRAPH_BREAK, false);
				XTextCursor cursor = doc.text.createTextCursorByRange(doc.text.getEnd());
				cursor.goLeft((short) 1, false);
				cursor.goLeft((short) 1, true);
				range = cursor;
			}
			
			named = (XNamed) UnoRuntime.queryInterface(XNamed.class,
					 doc.docFactory.createInstance("com.sun.star.text.TextSection"));
			named.setName(rawCode);
			textContent = (XTextContent) UnoRuntime.queryInterface(XTextContent.class, named);
			textContent.attach(range);
			
			isTextSection = true;
			isDisposable = true;
		}
	}
	
	protected void reattachMark() throws Exception {
		if(isTextSection) {
			XTextCursor cursor = doc.text.createTextCursorByRange(range);
			cursor.collapseToStart();
			cursor.goRight((short)1, true);
			cursor.setString("");
		} else {
			named = (XNamed) UnoRuntime.queryInterface(XNamed.class,
					doc.docFactory.createInstance("com.sun.star.text.ReferenceMark"));
			named.setName(rawCode);
			textContent = (XTextContent) UnoRuntime.queryInterface(XTextContent.class, named);
			textContent.attach(range);
		}
	}
	
	protected void moveCursorRight(XTextCursor cursor, int length) {
		short step;
		for(int i=length; i>0; i-=step) {
			step = (short) Math.min(i, 32767);
			cursor.goRight(step, true);
		}
	}
	
	private boolean isWholeRange() throws Exception {
		if(isNote) {
			// delete footnote, if this is the only thing in it
			XSimpleText noteSimpleText = (XSimpleText) UnoRuntime.queryInterface(XSimpleText.class, text);
			XTextRange noteRange = (XTextRange) UnoRuntime.queryInterface(XTextRange.class, noteSimpleText);
			
			return (textRangeCompare.compareRegionStarts(range, noteRange) == 0 && textRangeCompare.compareRegionEnds(range, noteRange) == 0);
		}
		return false;
	}
	
	private void getOutOfField() {
		try {
			XTextCursor cursor = doc.getSelection();
			if(cursor.isCollapsed() && textRangeCompare.compareRegionEnds(cursor, range) == 0) {
				cursor.gotoRange(range.getEnd(), false);
				XDispatchProvider dispatchProvider = (XDispatchProvider) UnoRuntime.
					queryInterface(XDispatchProvider.class, doc.controller); 
				XDispatchHelper dispatchHelper = (XDispatchHelper) UnoRuntime.
					queryInterface(XDispatchHelper.class, doc.factory.createInstance("com.sun.star.frame.DispatchHelper")); 
				dispatchHelper.executeDispatch(dispatchProvider, ".uno:ResetAttributes", "", 0, new PropertyValue[] {});
			}
		} catch(Exception e) {}
	}
	
	private void insertRTF(String text, XTextCursor cursor) throws Exception {
		PropertyValue filterName = new PropertyValue();
		filterName.Name = "FilterName";
		filterName.Value = "Rich Text Format";
		PropertyValue inputStream = new PropertyValue();
		inputStream.Name = "InputStream";
		try {
			inputStream.Value = new StringInputStream(text.getBytes("ISO-8859-1"));
		} catch (UnsupportedEncodingException e) {
			return;
		}
		
		((XDocumentInsertable) UnoRuntime.queryInterface(XDocumentInsertable.class, cursor)).
			insertDocumentFromURL("private:stream", new PropertyValue[] {filterName, inputStream});
	}
}
