package org.isaqb.onlineexam.mockexam.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.isaqb.onlineexam.mockexam.BuildInfo;
import org.isaqb.onlineexam.mockexam.DataConfiguration;
import org.isaqb.onlineexam.mockexam.loader.AsciidocReader;
import org.isaqb.onlineexam.mockexam.loader.IntroductionLoader;
import org.isaqb.onlineexam.mockexam.model.Exam;
import org.isaqb.onlineexam.mockexam.model.Exam.Mode;
import org.isaqb.onlineexam.mockexam.model.I18NText;
import org.isaqb.onlineexam.mockexam.model.Language;
import org.isaqb.onlineexam.mockexam.model.TaskAnswer;
import org.isaqb.onlineexam.mockexam.model.calculation.Calculator;
import org.isaqb.onlineexam.mockexam.util.Base64Handler;
import org.isaqb.onlineexam.mockexam.util.CookieHelper;
import org.isaqb.onlineexam.mockexam.util.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UiController {

    private IntroductionLoader introductionLoader;
    private JsonMapper jsonMapper;
    private I18NText cookieDislaimer;
    private I18NText howToUse;
    private AutoloadJS autoloadJS;
    private DataConfiguration quizConfiguration;
    private ExamHttpAdapter examHttpAdapter;
    private Base64Handler base64;
    private AsciidocReader adocReader;



    public UiController(
            ExamHttpAdapter examHttpAdapter,
            IntroductionLoader introductionLoader,
            JsonMapper jsonMapper,
            AsciidocReader adocReader,
            @Value("classpath:messages/cookie-disclaimer.adoc") Resource resourceCookieDisclaimer,
            @Value("classpath:messages/how-to-use.adoc") Resource howToUse,
            AutoloadJS autloadJS,
            DataConfiguration quizConfiguration,
            Base64Handler base64
    ) throws IOException {
        this.examHttpAdapter = examHttpAdapter;
        this.introductionLoader = introductionLoader;
        this.jsonMapper = jsonMapper;
        this.cookieDislaimer = parseADoc(adocReader, resourceCookieDisclaimer);
        this.howToUse = parseADoc(adocReader, howToUse);
        this.autoloadJS = autloadJS;
        this.quizConfiguration = quizConfiguration;
        this.base64 = base64;
        this.adocReader = adocReader;
    }

    private I18NText parseADoc(AsciidocReader adocReader, Resource resourceCookieDisclaimer) throws IOException {
        String adocCookieDisclaimer = IOUtils.toString(
            resourceCookieDisclaimer.getInputStream(),
            StandardCharsets.UTF_8);
        return adocReader.parse(adocCookieDisclaimer);
    }



    @GetMapping("introduction.html")
    public String introduction(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            @RequestParam("language") String language
    ) {

        new CookieHelper(request, response, base64).deleteAllCookies();

        response.addCookie(new Cookie("language", language));
        Language lang = Language.valueOf(language);

        model.addAttribute("language", language);
        model.addAttribute("html", introductionLoader.getHtml(lang));
        model.addAttribute("cookieDisclaimer", cookieDislaimer.getText(lang));
        model.addAttribute("howToUse", howToUse.getText(lang));
        model.addAttribute("appversion",
                String.format("Version %s - Build %s", BuildInfo.getVersion(), BuildInfo.getBuildTimestamp()));
        model.addAttribute("quizOptions", possibleQuizOptions(lang));
        autoloadJS.injectAutoReloadJS(model);

        return "introduction.html";
    }

    private List<QuizOptions> possibleQuizOptions(Language lang) {
        return quizConfiguration.getTasks().entrySet().stream()
                .map(e -> new QuizOptions(e.getKey(), e.getValue().getName().get(lang)))
                .toList();
    }



    @GetMapping("process-exam.html")
    public String processExam(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            @RequestParam(name = "language", required = false) String langParam,
            @CookieValue(name = "language", required = false) String language,
            @CookieValue(name = "givenAnswers", required = false) String givenAnswersJson,
            @RequestBody(required = false) MultiValueMap<String, String> formData
    ) {
        List<TaskAnswer> givenAnswers = givenAnswersFromCookie(givenAnswersJson);
        Exam exam = examHttpAdapter.from(request);
        examHttpAdapter.send(response, exam);

        String realLanguage = language != null ? language : langParam;

        UIData uiData = new UIData(adocReader, exam, Language.valueOf(realLanguage), givenAnswers, null);
        model.addAttribute("exam", exam);
        model.addAttribute("util", uiData);
        model.addAttribute("givenAnswers", givenAnswers);
        model.addAttribute("language", realLanguage);
        autoloadJS.injectAutoReloadJS(model);
        if (exam.getMode() == Mode.QUIZ) {
            model.addAttribute("quizhint", "Sie können diese URL speichern, um zukünftig direkt ein Quiz mit den gewählten Themen zu starten.");
        } else {
            model.addAttribute("quizhint", "");
        }

        response.addCookie(new Cookie("language", realLanguage));
        return "process-exam.html";
    }

    private List<TaskAnswer> givenAnswersFromCookie(String givenAnswersJson) {
        return givenAnswersJson == null ? Collections.emptyList() : jsonMapper.fromStringToAnswers(givenAnswersJson);
    }



    @PostMapping(value = "send-exam")
    public String sendExam(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            @RequestBody(required = false) MultiValueMap<String, String> formData
    ) {
        Exam exam = examHttpAdapter.from(request);
        Collection<TaskAnswer> givenAnswers = parse(formData);
        model.addAttribute("givenAnswers", givenAnswers);
        response.addCookie(new Cookie("givenAnswers", jsonMapper.toString(givenAnswers)));
        autoloadJS.injectAutoReloadJS(model);
        return answersMissing(exam, givenAnswers) ? "missing-tasks.html" : "redirect:/calculatePoints";
    }

    private boolean answersMissing(Exam exam, Collection<TaskAnswer> givenAnswers) {
        return givenAnswers.stream().filter(a -> !a.getOptionSelections().isEmpty()).count() < exam.getTasks().size();
    }

    private Collection<TaskAnswer> parse(MultiValueMap<String, String> formData) {
        if (formData == null) {
            return Collections.emptyList();
        }

        Map<Integer, TaskAnswer> answers = new HashMap<Integer, TaskAnswer>();

        for (Entry<String, List<String>> entry : formData.entrySet()) {
            String[] parts = entry.getKey().split("-");
            if ("frage".equals(parts[0])) {
                int nr = Integer.parseInt(parts[1]);
                String option = parts[2];
                var answer = answers.computeIfAbsent(nr, x -> new TaskAnswer(x));
                answer.put(option, entry.getValue());

            }
            if ("flag".equals(parts[0])) {
                int nr = Integer.parseInt(parts[1]);
                boolean flagged = entry.getValue().contains("on");
                var answer = answers.computeIfAbsent(nr, x -> new TaskAnswer(x));
                answer.setFlagged(flagged);
            }
        }

        return answers.values();
    }



    @GetMapping("calculatePoints")
    public String calculatePoints(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            Base64Handler base64,
            @CookieValue("language") String language,
            @CookieValue(name = "givenAnswers", required = false) String givenAnswersJsonBase64
    ) {
        Exam exam = examHttpAdapter.from(request);
        List<TaskAnswer> givenAnswers = givenAnswersFromCookie(givenAnswersJsonBase64);

        Calculator calc = new Calculator();
        var result = calc.calculate(exam, givenAnswers);
        model.addAttribute("result", result);
        response.addCookie(new Cookie("result", jsonMapper.toString(result)));

        UIData uiData = new UIData(adocReader, exam, Language.valueOf(language), givenAnswers, result);
        model.addAttribute("util", uiData);

        autoloadJS.injectAutoReloadJS(model);
        return "redirect:result-details.html";
    }



    @GetMapping("result-details.html")
    public String resultDetails(
            HttpServletRequest request,
            Model model,
            @CookieValue("language") String language,
            @CookieValue(name = "givenAnswers", required = false) String givenAnswersJsonBase64,
            @CookieValue("result") String resultJsonBase64
    ) {
        Exam exam = examHttpAdapter.from(request);
        List<TaskAnswer> givenAnswers = givenAnswersFromCookie(givenAnswersJsonBase64);

        var result = jsonMapper.fromStringToCalculationResult(resultJsonBase64);
        model.addAttribute("result", result);

        UIData uiData = new UIData(adocReader, exam, Language.valueOf(language), givenAnswers, result);
        model.addAttribute("util", uiData);

        autoloadJS.injectAutoReloadJS(model);
        return "result-details.html";
    }



    @GetMapping("end")
    public String end(HttpServletResponse response, HttpServletRequest request) {
        new CookieHelper(request, response, base64).deleteAllCookies();
        return "index.html";
    }

}
