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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Random;

import com.sun.star.awt.MessageBoxButtons;
import com.sun.star.awt.Rectangle;
import com.sun.star.awt.XMessageBox;
import com.sun.star.awt.XMessageBoxFactory;
import com.sun.star.awt.XWindowPeer;
import com.sun.star.beans.XPropertySet;
import com.sun.star.container.XEnumeration;
import com.sun.star.container.XEnumerationAccess;
import com.sun.star.container.XIndexAccess;
import com.sun.star.container.XNamed;
import com.sun.star.frame.XController;
import com.sun.star.frame.XDesktop;
import com.sun.star.frame.XFrame;
import com.sun.star.lang.XComponent;
import com.sun.star.lang.XMultiServiceFactory;
import com.sun.star.lang.XServiceInfo;
import com.sun.star.text.XBookmarksSupplier;
import com.sun.star.text.XParagraphCursor;
import com.sun.star.text.XReferenceMarksSupplier;
import com.sun.star.text.XText;
import com.sun.star.text.XTextContent;
import com.sun.star.text.XTextCursor;
import com.sun.star.text.XTextDocument;
import com.sun.star.text.XTextRange;
import com.sun.star.text.XTextRangeCompare;
import com.sun.star.text.XTextSection;
import com.sun.star.text.XTextSectionsSupplier;
import com.sun.star.text.XTextViewCursor;
import com.sun.star.text.XTextViewCursorSupplier;
import com.sun.star.uno.UnoRuntime;

public class Document {
	public static final int NOTE_FOOTNOTE = 1;
	public static final int NOTE_ENDNOTE = 2;
	
	Application app;
	public XTextRangeCompare textRangeCompare;
	public XText text;
	public XDesktop desktop;
	public XMultiServiceFactory docFactory;
	public XMultiServiceFactory factory;
	public XFrame frame;
	public XController controller;
	public XComponent component;
	public Properties properties;
	
	public static final String[] PREFIXES = {"ZOTERO_", "CITE_", " ADDIN ZOTERO_"};
	public static final String[] PREFS_PROPERTIES = {"ZOTERO_PREF", "CITE_PREF"};
	public static final String FIELD_PLACEHOLDER = "{Citation}";
	public static final String BOOKMARK_REFERENCE_PROPERTY = "ZOTERO_BREF_";
	
	public static int BOOKMARK_ADD_CHARS = 12;
	public static int REFMARK_ADD_CHARS = 10;
	public static String BIBLIOGRAPHY_CODE = "BIBL";
	
	public static String ERROR_STRING = "An error occurred communicating with Zotero:";
	
    public Document(Application anApp) throws Exception {
    	app = anApp;
    	factory = app.factory;
		desktop = app.desktop;
		frame = desktop.getCurrentFrame();
		component = desktop.getCurrentComponent();
		controller = frame.getController();
		docFactory = (XMultiServiceFactory) UnoRuntime.queryInterface(XMultiServiceFactory.class, component); 
		text = ((XTextDocument) UnoRuntime.queryInterface(XTextDocument.class, component)).getText();
		textRangeCompare = (XTextRangeCompare) UnoRuntime.queryInterface(XTextRangeCompare.class, text);
		properties = new Properties(desktop);
    }
    
    public void cleanup() {}
    
    public int displayAlert(String text, int icon, int buttons) {
    	// figure out appropriate buttons
    	int ooButtons = MessageBoxButtons.BUTTONS_OK;
    	if(buttons == 1) {
    		ooButtons = MessageBoxButtons.BUTTONS_OK_CANCEL + MessageBoxButtons.DEFAULT_BUTTON_OK;
    	} else if(buttons == 2) {
    		ooButtons = MessageBoxButtons.BUTTONS_YES_NO + MessageBoxButtons.DEFAULT_BUTTON_YES;
    	} else if(buttons == 3) {
    		ooButtons = MessageBoxButtons.BUTTONS_YES_NO_CANCEL + MessageBoxButtons.DEFAULT_BUTTON_YES;
    	} else {
    		ooButtons = MessageBoxButtons.BUTTONS_OK;
    	}
    	
    	final String[] boxTypes = {"errorbox", "messbox", "warningbox"};
    	
        XWindowPeer xWindow = (XWindowPeer) UnoRuntime.queryInterface(XWindowPeer.class, frame.getContainerWindow());
        XMessageBoxFactory xToolkit = (XMessageBoxFactory) UnoRuntime.queryInterface(XMessageBoxFactory.class, xWindow.getToolkit());
		XMessageBox box = xToolkit.createMessageBox(xWindow, new Rectangle(), boxTypes[icon], ooButtons, "Zotero Integration", text);
		short result = box.execute();
		
		if(buttons == 2) {
			return (result == 3 ? 0 : 1);
		} if(buttons == 3 && result == 3) {
			return 1;
		} else if(buttons == 0) {
			return 0;
		}
		return result;
    }
        
    public void activate() {
    	try {
	    	if(System.getProperty("os.name").equals("Mac OS X")) {
	    		Runtime runtime = Runtime.getRuntime();
	    		runtime.exec(new String[] {"/usr/bin/osascript", "-e", "tell application \""+app.ooName+"\" to activate"});
	    	}
    	} catch(Exception e) {}
    }
    
    public boolean canInsertField(String fieldType) {
    	try {
	    	// first, check if cursor is in the bibliography (no sense offering to replace it)
	    	XTextViewCursor selection = getSelection();
	    	XTextSection section = (XTextSection) UnoRuntime.queryInterface(XTextSection.class, selection);
	    	if(section != null) {
	    		XNamed sectionNamed = (XNamed) UnoRuntime.queryInterface(XNamed.class, section);
	    		String name = sectionNamed.getName();
	    		for(String prefix : PREFIXES) {
	    			if(name.startsWith(prefix)) {
	    				return false;
	    			}
	    		}
	    	}
	    	
	    	// Also make sure that the cursor is not in any other place we can't insert a citation
	    	String position = getRangePosition(selection);
	    	// TODO: tables?
			return (position.equals("SwXBodyText") || (!fieldType.equals("Bookmark") && position.equals("SwXFootnote")));
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    	return false;
	    }
	}

    public ReferenceMark cursorInField(String fieldType) {
		try {
	    	Object mark;
    		
	    	// create two text cursors containing the selection
	    	XTextViewCursor selectionCursor = getSelection();
	    	XText text = selectionCursor.getText();
	       	XParagraphCursor paragraphCursor1 = (XParagraphCursor) UnoRuntime.queryInterface(XParagraphCursor.class,
	       			text.createTextCursorByRange(selectionCursor));
	    	XParagraphCursor paragraphCursor2 = (XParagraphCursor) UnoRuntime.queryInterface(XParagraphCursor.class,
	    			text.createTextCursorByRange(selectionCursor));
	    	
	    	// extend one cursor to the beginning of the paragraph and one to the end
	    	paragraphCursor1.goLeft((short) 1, false);
	    	paragraphCursor1.gotoStartOfParagraph(true);
	    	paragraphCursor2.gotoEndOfParagraph(true);
	    	
	    	// get enumerator corresponding to first cursor
	    	XEnumerationAccess enumeratorAccess = (XEnumerationAccess) UnoRuntime.queryInterface(XEnumerationAccess.class, paragraphCursor1);
	    	Object nextElement = enumeratorAccess.createEnumeration().nextElement();
	    	enumeratorAccess = (XEnumerationAccess) UnoRuntime.queryInterface(XEnumerationAccess.class, nextElement);
	    	XEnumeration enumerator = enumeratorAccess.createEnumeration();
	    	
	    	while(enumerator.hasMoreElements()) {
	    		// look for a ReferenceMark or Bookmark
	    		XPropertySet textProperties = (XPropertySet) UnoRuntime.queryInterface(XPropertySet.class, enumerator.nextElement());
	    		String textPropertyType = (String) textProperties.getPropertyValue("TextPortionType");
	    		System.out.println(textPropertyType);
	    		if(textPropertyType.equals(fieldType)) {
	        		mark = textProperties.getPropertyValue(fieldType);
	        		
	    			// see if it has an appropriate prefix
	    			XNamed named = (XNamed) UnoRuntime.queryInterface(XNamed.class, mark);
	    			String name = named.getName();
	    			for(String prefix : PREFIXES) {
	    				if(name.startsWith(prefix)) {
							// check second enumerator for the same field
	    			    	enumeratorAccess = (XEnumerationAccess) UnoRuntime.queryInterface(XEnumerationAccess.class, paragraphCursor2);
	    			    	nextElement = enumeratorAccess.createEnumeration().nextElement();
	    			    	enumeratorAccess = (XEnumerationAccess) UnoRuntime.queryInterface(XEnumerationAccess.class, nextElement);
	    			    	XEnumeration enumerator2 = enumeratorAccess.createEnumeration();
	    			    	while(enumerator2.hasMoreElements()) {
	    			    		textProperties = (XPropertySet) UnoRuntime.queryInterface(XPropertySet.class, enumerator2.nextElement());
	    			    		textPropertyType = (String) textProperties.getPropertyValue("TextPortionType");
	    			    		if(textPropertyType.equals(fieldType)) {
	    			        		mark = textProperties.getPropertyValue(fieldType);
	    			        		
	    			    			named = (XNamed) UnoRuntime.queryInterface(XNamed.class, mark);
	    			    			String name2 = named.getName();
	    			    			if(name.equals(name2)) {
	    			    				if(textPropertyType.equals("ReferenceMark")) {
	    			    					return new ReferenceMark(this, named, name);
	    			    				} else {
	    			    					return new Bookmark(this, named, name);
	    			    				}
	    			    			}
	    			    		}
	    			    	}
	    				}
	    			}
	    		}
	    	}
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    }
    	
    	return null;
    }
    
    public String getDocumentData() throws Exception {
    	try {
	    	String data;
	    	for(String prefsProperty : PREFS_PROPERTIES) {
	    		data = properties.getProperty(prefsProperty);
	    		if(data != "") return data;
	    	}
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    }
    	return "";
    }
    
    public void setDocumentData(String data) throws Exception {
    	try {
    		properties.setProperty(PREFS_PROPERTIES[0], data);
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    }
    }
    
    public MarkEnumerator getFields(String fieldType) {
    	try {
	    	LinkedList<ReferenceMark> marks = new LinkedList<ReferenceMark>();
	    	
	    	// get all ReferenceMarks/Bookmarks
	    	if(fieldType.equals("ReferenceMark")) {
	    		XReferenceMarksSupplier referenceMarksSupplier = (XReferenceMarksSupplier) 
	    			UnoRuntime.queryInterface(XReferenceMarksSupplier.class, component);
				XIndexAccess markIndexAccess = (XIndexAccess) UnoRuntime.queryInterface(XIndexAccess.class,
						referenceMarksSupplier.getReferenceMarks());
				int count = markIndexAccess.getCount();
				for(int i = 0; i<count; i++) {
					Object aMark = markIndexAccess.getByIndex(i);
					XNamed named = ((XNamed) UnoRuntime.queryInterface(XNamed.class, aMark));
					String name = named.getName();
					
					for(String prefix : Document.PREFIXES) {
						if(name.startsWith(prefix)) {
							marks.add(new ReferenceMark(this, named, name));
							break;
						}
					}
				}
	    		
	    		XTextSectionsSupplier textSectionSupplier = (XTextSectionsSupplier) 
	    			UnoRuntime.queryInterface(XTextSectionsSupplier.class, component);
				markIndexAccess = (XIndexAccess) UnoRuntime.queryInterface(XIndexAccess.class,
						textSectionSupplier.getTextSections());
				count = markIndexAccess.getCount();
				for(int i = 0; i<count; i++) {
					Object aMark = markIndexAccess.getByIndex(i);
					XNamed named = ((XNamed) UnoRuntime.queryInterface(XNamed.class, aMark));
					String name = named.getName();
					
					for(String prefix : Document.PREFIXES) {
						if(name.startsWith(prefix)) {
							marks.add(new ReferenceMark(this, named, name));
							break;
						}
					}
				}
	    	} else if(fieldType.equals("Bookmark")) {
	    		XBookmarksSupplier bookmarksSupplier = (XBookmarksSupplier) 
	    			UnoRuntime.queryInterface(XBookmarksSupplier.class, component);
	    		XIndexAccess markIndexAccess = (XIndexAccess) UnoRuntime.queryInterface(XIndexAccess.class,
	    				bookmarksSupplier.getBookmarks());
				int count = markIndexAccess.getCount();
				for(int i = 0; i<count; i++) {
					Object aMark = markIndexAccess.getByIndex(i);
					XNamed named = ((XNamed) UnoRuntime.queryInterface(XNamed.class, aMark));
					String name = named.getName();
					
					for(String prefix : Document.PREFIXES) {
						if(name.startsWith(prefix)) {
							marks.add(new Bookmark(this, named, name));
							break;
						}
					}
				}
	    	} else {
	    		throw new Exception("Invalid field type "+fieldType);
	    	}
	    	
	    	Collections.sort(marks);
	    	return new MarkEnumerator(marks);
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    	return null;
	    }
    }
    
    public ReferenceMark insertField(String fieldType, int noteType) throws Exception {
    	try {
    		// duplicate selection cursor
    		XTextViewCursor selectionCursor = getSelection();
	    	XTextCursor rangeToInsert = (XParagraphCursor) UnoRuntime.queryInterface(XParagraphCursor.class,
	       			selectionCursor.getText().createTextCursorByRange(selectionCursor));
	    	
	    	return insertMarkAtRange(fieldType, noteType, rangeToInsert, null, null);
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    	return null;
	    }
    }
    
    public void convert(ReferenceMark mark, String fieldType, int noteType) {
    	try {
    		XTextCursor range = mark.getReplacementCursor();
    		
    		boolean isBookmark = mark instanceof Bookmark;
    		if(isBookmark && fieldType.equals("Bookmark")) {
    			// convert from one bookmark type to another
	    		insertMarkAtRange(fieldType, noteType, range, null, mark.rawCode);
    		} else if(!isBookmark && fieldType.equals("ReferenceMark")) {
    			// convert from one referenceMark type to another
	    		insertMarkAtRange(fieldType, noteType, range, mark.rawCode, null);
    		} else {
	    		String code = mark.getCode();
	    		ReferenceMark newMark = insertMarkAtRange(fieldType, noteType, range, null, null);
	    		newMark.setCode(code);
    		}
    	} catch(Exception e) {
	    	displayAlert(getErrorString(e), 0, 0);
	    }
    }
    
    public XTextViewCursor getSelection() {
    	XTextViewCursorSupplier supplier = (XTextViewCursorSupplier) UnoRuntime.queryInterface(XTextViewCursorSupplier.class, controller);
    	return supplier.getViewCursor();
    }
    
    private ReferenceMark insertMarkAtRange(String fieldType, int noteType, XTextCursor rangeToInsert, String code, String customBookmarkName) throws Exception {    	
    	XNamed mark;
    	String rawCode;

    	// handle null code
    	if(code == null) {
    		code = PREFIXES[0];
    	}

    	// make footnote or endnote if cursor is in body text and a note style is selected
    	if(noteType != 0 && getRangePosition(rangeToInsert).equals("SwXBodyText")) {
    		Object note;
    		if(noteType == NOTE_FOOTNOTE) {
    			note = docFactory.createInstance("com.sun.star.text.Footnote");
    		} else {
    			note = docFactory.createInstance("com.sun.star.text.Endnote");
    		}
    		XTextContent noteTextContent = (XTextContent) UnoRuntime.queryInterface(XTextContent.class, note);
    		rangeToInsert.getText().insertTextContent(rangeToInsert, noteTextContent, true);
    		XTextRange noteTextRange = (XTextRange) UnoRuntime.queryInterface(XTextRange.class, note);
    		rangeToInsert = noteTextRange.getText().createTextCursorByRange(noteTextRange);
    	}

    	rangeToInsert.setString(FIELD_PLACEHOLDER);

    	// create mark
    	if(fieldType.equals("ReferenceMark")) {
    		mark = (XNamed) UnoRuntime.queryInterface(XNamed.class,
    				docFactory.createInstance("com.sun.star.text.ReferenceMark"));
    		rawCode = code + " RND" + Document.getRandomString(Document.REFMARK_ADD_CHARS);
    		mark.setName(rawCode);
    	} else if(fieldType.equals("Bookmark")) {
        	// determine appropriate name for the bookmark
        	rawCode = customBookmarkName;
        	if(rawCode == null) {
        		rawCode = BOOKMARK_REFERENCE_PROPERTY+getRandomString(BOOKMARK_ADD_CHARS);
        	}
        	
    		mark = (XNamed) UnoRuntime.queryInterface(XNamed.class,
    				docFactory.createInstance("com.sun.star.text.Bookmark"));
    		mark.setName(rawCode);
    	} else {
    		throw new Exception("Invalid field type "+fieldType);
    	}

    	// attach field to range
    	XTextContent markContent = (XTextContent) UnoRuntime.queryInterface(XTextContent.class, mark);
    	markContent.attach(rangeToInsert);

    	// refmarks have code already set
    	if(fieldType.equals("ReferenceMark")) {
    		return new ReferenceMark(this, mark, rawCode);
    	}

    	// set code for a bookmark
    	ReferenceMark newMark = new Bookmark(this, mark, rawCode);
    	if(customBookmarkName == null) newMark.setCode(code);
    	return newMark;
    }
    
    private String getRangePosition(XTextRange selection) {
    	XServiceInfo serviceInfo = (XServiceInfo) UnoRuntime.queryInterface(XServiceInfo.class, selection.getText());
    	return serviceInfo.getImplementationName();
	}

	private static final String randomCharacterSet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
	private static Random rand = null;
    public static String getRandomString(int len) {
    	if(rand == null) rand = new Random();
    	StringBuilder sb = new StringBuilder(len);
    	for(int i = 0; i < len; i++) sb.append(randomCharacterSet.charAt(rand.nextInt(randomCharacterSet.length())));
    	return sb.toString();
    }
    
    public static String getErrorString(Exception e) {
    	StringWriter sw = new StringWriter();
    	e.printStackTrace(new PrintWriter(sw));
    	return "An error occurred communicating with Zotero:\n"+sw.toString();
    }
}