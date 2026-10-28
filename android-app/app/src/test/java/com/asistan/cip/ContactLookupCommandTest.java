package com.asistan.cip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ContactLookupCommandTest {
    @Test
    public void extractsNamesOnlyFromExplicitContactLookupRequests() {
        assertEquals("ayse", ContactLookupCommand.extractName("Rehberde Ayşe'yi ara"));
        assertEquals("mehmet", ContactLookupCommand.extractName("Mehmet'in numarası ne?"));
        assertEquals("zeynep kaya",
                ContactLookupCommand.extractName("Kişilerimde Zeynep Kaya'yı bul"));
    }

    @Test
    public void doesNotRequestContactLookupForGeneralOrPickerCommands() {
        assertNull(ContactLookupCommand.extractName("kişiyi ara"));
        assertNull(ContactLookupCommand.extractName("telefonumdaki her şeyi aç"));
        assertNull(ContactLookupCommand.extractName("hava nasıl"));
    }
}
