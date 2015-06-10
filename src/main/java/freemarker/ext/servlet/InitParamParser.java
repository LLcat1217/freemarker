package freemarker.ext.servlet;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.cache.WebappTemplateLoader;
import freemarker.core._ObjectBuilderSettingEvaluator;
import freemarker.core._SettingEvaluationEnvironment;
import freemarker.log.Logger;
import freemarker.template.Configuration;
import freemarker.template._TemplateAPI;
import freemarker.template.utility.StringUtil;


final class InitParamParser {

    static final String TEMPLATE_PATH_PREFIX_CLASS = "class://";
    static final String TEMPLATE_PATH_PREFIX_CLASSPATH = "classpath:";
    static final String TEMPLATE_PATH_PREFIX_FILE = "file://";
    static final String TEMPLATE_PATH_SETTINGS_BI_NAME = "settings";
    
    private static final Logger LOG = Logger.getLogger("freemarker.servlet");
    
    private InitParamParser() {
        // Not to be instantiated
    }

    static TemplateLoader createTemplateLoader(
            String templatePath, Configuration cfg, Class classLoaderClass, ServletContext srvCtx)
            throws IOException {
        int settingAssignmentsStart = findTemplatePathSettingAssignmentsStart(templatePath);
        String pureTemplatePath = (settingAssignmentsStart == -1 ? templatePath : templatePath.substring(0, settingAssignmentsStart))
                .trim();
        
        final TemplateLoader templateLoader;
        if (pureTemplatePath.startsWith(TEMPLATE_PATH_PREFIX_CLASS)) {
            String packagePath = pureTemplatePath.substring(TEMPLATE_PATH_PREFIX_CLASS.length());
            packagePath = normalizeToAbsolutePackagePath(packagePath);
            templateLoader = new ClassTemplateLoader(classLoaderClass, packagePath);
        } else if (pureTemplatePath.startsWith(TEMPLATE_PATH_PREFIX_CLASSPATH)) {
            // To be similar to Spring resource paths, we don't require "//":
            String packagePath = pureTemplatePath.substring(TEMPLATE_PATH_PREFIX_CLASSPATH.length());
            packagePath = normalizeToAbsolutePackagePath(packagePath);
            
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            if (classLoader == null) {
                LOG.warn("No Thread Context Class Loader was found. Falling back to the class loader of "
                        + classLoaderClass.getName() + ".");
                classLoader = classLoaderClass.getClassLoader();
            }
            
            templateLoader = new ClassTemplateLoader(classLoader, packagePath);
        } else if (pureTemplatePath.startsWith(TEMPLATE_PATH_PREFIX_FILE)) {
            String filePath = pureTemplatePath.substring(TEMPLATE_PATH_PREFIX_FILE.length());
            templateLoader = new FileTemplateLoader(new File(filePath));
        } else if (pureTemplatePath.startsWith("[")
                && cfg.getIncompatibleImprovements().intValue() >= _TemplateAPI.VERSION_INT_2_3_22) {
            if (!pureTemplatePath.endsWith("]")) {
                // B.C. constraint: Can't throw any checked exceptions.
                throw new TemplatePathParsingException("Failed to parse template path; closing \"]\" is missing.");
            }
            List pathItems;
            try {
                pathItems = parseCommaSeparatedList(pureTemplatePath.substring(1, pureTemplatePath.length() - 1));
            } catch (ParseException e) {
                // B.C. constraint: Can't throw any checked exceptions.
                throw new TemplatePathParsingException("Failed to parse template path; see cause exception.", e);
            }
            TemplateLoader[] templateLoaders = new TemplateLoader[pathItems.size()];
            for (int i = 0; i < pathItems.size(); i++) {
                String pathItem = (String) pathItems.get(i);
                templateLoaders[i] = createTemplateLoader(pathItem, cfg, classLoaderClass, srvCtx);
            }
            templateLoader = new MultiTemplateLoader(templateLoaders);
        } else if (pureTemplatePath.startsWith("{")
                && cfg.getIncompatibleImprovements().intValue() >= _TemplateAPI.VERSION_INT_2_3_22) {
            throw new TemplatePathParsingException("Template paths starting with \"{\" are reseved for future purposes");
        } else {
            templateLoader = new WebappTemplateLoader(srvCtx, pureTemplatePath);
        }
        
        if (settingAssignmentsStart != -1) {
            try {
                int nextPos = _ObjectBuilderSettingEvaluator.configureBean(
                        templatePath, templatePath.indexOf('(', settingAssignmentsStart) + 1, templateLoader,
                        _SettingEvaluationEnvironment.getCurrent());
                if (nextPos != templatePath.length()) {
                    throw new TemplatePathParsingException("Template path should end after the setting list in: "
                + templatePath);
                }
            } catch (Exception e) {
                throw new TemplatePathParsingException("Failed to set properties in: " + templatePath, e);
            }
        }
        
        return templateLoader;
    }

    static String normalizeToAbsolutePackagePath(String path) {
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return "/" + path;
    }

    static List/*<String>*/ parseCommaSeparatedList(String value) throws ParseException {
        List/*<String>*/ valuesList = new ArrayList();
        String[] values = StringUtil.split(value, ',');
        for (int i = 0; i < values.length; i++) {
            final String s = values[i].trim();
            if (s.length() != 0) {
                valuesList.add(s);
            } else if (i != values.length - 1) {
                throw new ParseException("Missing list item after a comma", -1);
            }
        }
        return valuesList;
    }

    static List parseCommaSeparatedPatterns(String value) throws ParseException {
        List/*<String>*/ values = parseCommaSeparatedList(value);
        List/*<Pattern>*/ patterns = new ArrayList(values.size());
        for (int i = 0; i < values.size(); i++) {
            patterns.add(Pattern.compile((String) values.get(i)));
        }
        return patterns;
    }
    
    /**
     * @return -1 if there's no setting assignment.
     */
    static int findTemplatePathSettingAssignmentsStart(String s) {
        int pos = s.length() - 1;
        
        // Skip WS
        while (pos >= 0 && Character.isWhitespace(s.charAt(pos))) {
            pos--;
        }
        
        // Skip `)`
        if (pos < 0 || s.charAt(pos) != ')') return -1;
        pos--;
        
        // Skip `(...`
        int parLevel = 1;
        int mode = 0;
        while (parLevel > 0) {
            if (pos < 0) return -1;
            char c = s.charAt(pos);
            switch (mode) {
            case 0:  // 0: outside string literal
                switch (c) {
                case '(': parLevel--; break;
                case ')': parLevel++; break;
                case '\'': mode = 1; break;
                case '"': mode = 2; break;
                }
                break;
            case 1:  // 1: inside '...'
                if (c == '\'' && !(pos > 0 && s.charAt(pos - 1) == '\\')) {
                    mode = 0;
                }
                break;
            case 2:  // 2: inside "..."
                if (c == '"' && !(pos > 0 && s.charAt(pos - 1) == '\\')) {
                    mode = 0;
                }
                break;
            }
            pos--;
        }

        // Skip WS
        while (pos >= 0 && Character.isWhitespace(s.charAt(pos))) {
            pos--;
        }
        
        int biNameEnd = pos + 1;
        
        // Skip name chars
        while (pos >= 0 && Character.isJavaIdentifierPart(s.charAt(pos))) {
            pos--;
        }
        
        int biNameStart = pos + 1;
        if (biNameStart == biNameEnd) {
            return -1;
        }
        String biName = s.substring(biNameStart, biNameEnd);
        
        // Skip WS
        while (pos >= 0 && Character.isWhitespace(s.charAt(pos))) {
            pos--;
        }
        
        // Skip `?`
        if (pos < 0 || s.charAt(pos) != '?') return -1;
        
        if (!biName.equals(TEMPLATE_PATH_SETTINGS_BI_NAME)) {
            throw new TemplatePathParsingException(
                    StringUtil.jQuote(biName) + " is unexpected after the \"?\". "
                    + "Expected \"" + TEMPLATE_PATH_SETTINGS_BI_NAME + "\".");
        }
        
        return pos;
    }
    
    private static final class TemplatePathParsingException extends RuntimeException {

        public TemplatePathParsingException(String message, Throwable cause) {
            super(message, cause);
        }

        public TemplatePathParsingException(String message) {
            super(message);
        }
        
    }
    
}