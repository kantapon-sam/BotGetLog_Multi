package com.java.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LoginRecipe {

    private final String siteId;
    private final List<String> usernameSelectors;
    private final List<String> passwordSelectors;
    private final List<String> submitSelectors;
    private final List<String> nextSelectors;
    private final boolean autoSubmit;

    public LoginRecipe(String siteId, List<String> usernameSelectors, List<String> passwordSelectors,
            List<String> submitSelectors, List<String> nextSelectors, boolean autoSubmit) {
        this.siteId = siteId == null ? "" : siteId;
        this.usernameSelectors = copyOf(usernameSelectors);
        this.passwordSelectors = copyOf(passwordSelectors);
        this.submitSelectors = copyOf(submitSelectors);
        this.nextSelectors = copyOf(nextSelectors);
        this.autoSubmit = autoSubmit;
    }

    public static LoginRecipe generic(String siteId) {
        return new LoginRecipe(
                siteId,
                Arrays.asList(
                        "#username", "#user", "#login", "#email",
                        "input[name='username']",
                        "input[name='user']",
                        "input[name='login']",
                        "input[name='email']",
                        "input[type='email']"
                ),
                Arrays.asList(
                        "#password", "#pass", "#passwd",
                        "input[name='password']",
                        "input[name='pass']",
                        "input[type='password']"
                ),
                Arrays.asList(
                        "#loginBtn", "#btnLogin", "#signin", "#signInButton",
                        "button[type='submit']",
                        "input[type='submit']",
                        "button[name='login']",
                        "button[id*='login']",
                        "button[id*='sign']"
                ),
                Arrays.asList(
                        "#nextBtn",
                        "button[id*='next']",
                        "button[name='next']",
                        "input[type='submit']"
                ),
                true
        );
    }

    public String getSiteId() {
        return siteId;
    }

    public boolean isAutoSubmit() {
        return autoSubmit;
    }

    public String buildAutomationScript(String username, String password) {
        StringBuilder script = new StringBuilder();
        script.append("(function(){");
        script.append("var USER=").append(toJsString(username)).append(";");
        script.append("var PASS=").append(toJsString(password)).append(";");
        script.append("var USER_SELECTORS=").append(toJsArray(usernameSelectors)).append(";");
        script.append("var PASS_SELECTORS=").append(toJsArray(passwordSelectors)).append(";");
        script.append("var SUBMIT_SELECTORS=").append(toJsArray(submitSelectors)).append(";");
        script.append("var NEXT_SELECTORS=").append(toJsArray(nextSelectors)).append(";");
        script.append("var AUTO_SUBMIT=").append(autoSubmit ? "true" : "false").append(";");
        script.append("function list(selector){try{return Array.prototype.slice.call(document.querySelectorAll(selector));}catch(e){return [];}}");
        script.append("function visible(el){if(!el){return false;}var s=window.getComputedStyle(el);return s&&s.display!=='none'&&s.visibility!=='hidden'&&el.offsetWidth>0&&el.offsetHeight>0&&!el.disabled;}");
        script.append("function bySelectors(selectors){for(var i=0;i<selectors.length;i++){var items=list(selectors[i]);for(var j=0;j<items.length;j++){if(visible(items[j])){return items[j];}}}return null;}");
        script.append("function firstVisible(elements){for(var i=0;i<elements.length;i++){if(visible(elements[i])){return elements[i];}}return null;}");
        script.append("function setValue(el,value){if(!el){return;}el.focus();var proto=Object.getPrototypeOf(el);var desc=proto?Object.getOwnPropertyDescriptor(proto,'value'):null;if(desc&&desc.set){desc.set.call(el,value);}else{el.value=value;}el.setAttribute('value',value);['input','change','blur'].forEach(function(name){try{el.dispatchEvent(new Event(name,{bubbles:true}));}catch(ignore){}});}");
        script.append("function textValue(el){if(!el){return '';}return ((el.innerText||el.textContent||el.value||'')+'').toLowerCase();}");
        script.append("function attrValue(el,name){if(!el){return '';}return ((el.getAttribute(name)||'')+'').toLowerCase();}");
        script.append("function labelText(el){if(!el){return '';}var result='';if(el.id){var found=document.querySelector('label[for=\"'+el.id.replace(/\"/g,'\\\\\"')+'\"]');if(found){result+=' '+textValue(found);}}var parent=el.parentElement;var guard=0;while(parent&&guard<3){if(parent.tagName&&parent.tagName.toLowerCase()==='label'){result+=' '+textValue(parent);break;}parent=parent.parentElement;guard++;}return result;}");
        script.append("function loginHintText(el){return [attrValue(el,'id'),attrValue(el,'name'),attrValue(el,'placeholder'),attrValue(el,'aria-label'),attrValue(el,'autocomplete'),labelText(el)].join(' ');}");
        script.append("function isLoginLikeField(el){if(!el){return false;}var type=((el.getAttribute('type')||'text')+'').toLowerCase();if(type==='email'){return true;}var hint=loginHintText(el);var keys=['user','username','login','email','mail','account','employee','userid','sso','sign in','log in'];for(var i=0;i<keys.length;i++){if(hint.indexOf(keys[i])>=0){return true;}}return false;}");
        script.append("function likelyTextInput(inputs,requireLoginHint){for(var i=0;i<inputs.length;i++){var el=inputs[i];var type=((el.getAttribute('type')||'text')+'').toLowerCase();if(!visible(el)){continue;}if(type==='hidden'||type==='checkbox'||type==='radio'||type==='password'||type==='submit'||type==='button'||type==='search'){continue;}if(requireLoginHint&& !isLoginLikeField(el)){continue;}return el;}return null;}");
        script.append("function likelyButton(root,selectors,textHints){var btn=bySelectors(selectors);if(btn){return btn;}var scope=root||document;var items=Array.prototype.slice.call(scope.querySelectorAll('button,input[type=\"submit\"],input[type=\"button\"],a'));for(var i=0;i<items.length;i++){var el=items[i];if(!visible(el)){continue;}var text=textValue(el);for(var j=0;j<textHints.length;j++){if(text.indexOf(textHints[j])>=0){return el;}}}return null;}");
        script.append("var passwordInput=bySelectors(PASS_SELECTORS);if(!passwordInput){passwordInput=firstVisible(list('input[type=\"password\"]'));}");
        script.append("var usernameInput=bySelectors(USER_SELECTORS);");
        script.append("if(!usernameInput&&passwordInput&&passwordInput.form){usernameInput=likelyTextInput(Array.prototype.slice.call(passwordInput.form.querySelectorAll('input')),false);}");
        script.append("if(!usernameInput){usernameInput=likelyTextInput(Array.prototype.slice.call(document.querySelectorAll('input')),passwordInput==null);}");
        script.append("if(!usernameInput&&!passwordInput){return 'DONE:no-login-form';}");
        script.append("if(usernameInput&&USER){setValue(usernameInput,USER);}");
        script.append("if(passwordInput&&PASS){setValue(passwordInput,PASS);}");
        script.append("if(usernameInput&&!passwordInput){var nextButton=likelyButton(usernameInput.form,NEXT_SELECTORS,['next','continue','sign in','login','เข้าสู่ระบบ']);if(nextButton){nextButton.click();return 'RETRY:advanced';}return 'RETRY:password-not-found';}");
        script.append("if(!usernameInput){return 'RETRY:username-not-found';}");
        script.append("if(!passwordInput){return 'RETRY:password-not-found';}");
        script.append("var submitButton=likelyButton(passwordInput.form||usernameInput.form,SUBMIT_SELECTORS,['login','log in','sign in','submit','เข้าสู่ระบบ']);");
        script.append("if(AUTO_SUBMIT&&submitButton){submitButton.click();return 'SUBMITTED';}");
        script.append("return AUTO_SUBMIT?'FILLED:no-submit':'FILLED';");
        script.append("})()");
        return script.toString();
    }

    private static List<String> copyOf(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(source));
    }

    private static String toJsArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(toJsString(values.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }

    private static String toJsString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }
}
