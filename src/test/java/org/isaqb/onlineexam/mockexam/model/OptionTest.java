package org.isaqb.onlineexam.mockexam.model;

import static org.isaqb.onlineexam.mockexam.model.Language.DE;
import static org.isaqb.onlineexam.mockexam.model.Language.EN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class OptionTest {

	@Test
	public void findNone() {
		assertTrue(Option.findByPosition(options(), 'z').isEmpty());
	}
	
	@Test
	public void findOne() {
		assertEquals(1, Option.findByPosition(options(), 'a').size());
		assertEquals("Eins", Option.findByPosition(options(), 'a').get(0).getText(DE));
		assertEquals("Eins", Option.findByPosition(options(), 'a').get(0).getText(DE));
	}
	
	@Test
	public void findSeveral() {
		assertEquals(2, Option.findByPosition(options(), 'a', 'b').size());
	}
	
	private List<Option> options() {
		return List.of(
			new Option('a', true, Map.of(DE, "Eins", EN, "One")), 
			new Option('b', true, Map.of(DE, "Zwei", EN, "Two")), 
			new Option('c', true, Map.of(DE, "Duplikat", EN, "Duplicate")), 
			new Option('d', true, Map.of(DE, "Duplikat", EN, "Duplicate"))
		);
	}
	
}
