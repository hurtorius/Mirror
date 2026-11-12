package org.hurtorius.mirror.core;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
/** Resolve an explicitly submitted address or search; never navigate while the user is typing. */
public final class BrowserAddress {
    private BrowserAddress(){}
    public static String resolve(String input){
        if(input==null||input.isBlank())throw new IllegalArgumentException("Enter a website or a search first.");
        String value=input.strip();if(value.length()>2048)throw new IllegalArgumentException("That address is too long.");
        if(value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")||value.startsWith("javascript:")||value.startsWith("file:")||value.startsWith("data:"))return ScreenSpec.webUri(value).toString();
        if(!value.contains(" ")&&(value.contains(".")||value.startsWith("localhost")))return ScreenSpec.webUri((value.startsWith("localhost")||value.startsWith("127.")?"http://":"https://")+value).toString();
        return "https://www.google.com/search?q="+URLEncoder.encode(value,StandardCharsets.UTF_8);
    }
}
