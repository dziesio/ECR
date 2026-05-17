package com.ecrharv.harvester.service;

import com.ecrharv.harvester.entity.Announcement;
import com.ecrharv.harvester.entity.Attendance;
import com.ecrharv.harvester.entity.Grade;
import com.ecrharv.harvester.entity.Message;
import com.ecrharv.harvester.entity.Person;
import com.ecrharv.harvester.entity.Source;
import com.ecrharv.harvester.entity.Student;
import com.ecrharv.harvester.repository.AnnouncementRepository;
import com.ecrharv.harvester.repository.AttendanceRepository;
import com.ecrharv.harvester.repository.GradeRepository;
import com.ecrharv.harvester.repository.MessageRepository;
import com.ecrharv.harvester.repository.PersonRepository;
import com.ecrharv.harvester.repository.SourceRepository;
import com.ecrharv.harvester.repository.StudentRepository;
import com.ecrharv.harvester.scraping.ScrapedData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataPersistenceService {

    private final StudentRepository      studentRepository;
    private final GradeRepository        gradeRepository;
    private final MessageRepository      messageRepository;
    private final AttendanceRepository   attendanceRepository;
    private final AnnouncementRepository announcementRepository;
    private final SourceRepository       sourceRepository;
    private final PersonRepository       personRepository;

    private final java.util.Map<String, Source> sourceCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Transactional
    public Student findOrCreateStudent(String librusUsername, String fullName, String className) {
        return studentRepository.findByLibrusUsername(librusUsername)
                .map(s -> {
                    if (fullName  != null && !fullName.isBlank()) s.setFullName(fullName);
                    if (className != null) s.setClassName(className);
                    return studentRepository.save(s);
                })
                .orElseGet(() -> {
                    Student s = new Student();
                    s.setLibrusUsername(librusUsername);
                    s.setFullName(fullName);
                    s.setClassName(className);
                    log.info("Creating student record for '{}'", librusUsername);
                    return studentRepository.save(s);
                });
    }

    @Transactional(readOnly = true)
    public Set<String> getExistingMessageIds() {
        return messageRepository.findAllLibrusMessageIds();
    }

    @Transactional(readOnly = true)
    public Set<String> getExistingAnnouncementIds() {
        return announcementRepository.findAllLibrusAnnouncementIds();
    }

    @Transactional
    public void saveGrades(Student student, List<ScrapedData.GradeRecord> records) {
        int saved = 0, skipped = 0;

        for (ScrapedData.GradeRecord r : records) {
            boolean duplicate = gradeRepository.existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
                    student, r.subjectName(), r.gradeValue(), r.dateIssued()
            );
            if (duplicate) {
                skipped++;
                continue;
            }

            Grade g = new Grade();
            g.setStudent(student);
            g.setSubjectName(r.subjectName());
            g.setCategory(r.category());
            g.setGradeValue(r.gradeValue());
            g.setWeight(r.weight());
            g.setDateIssued(r.dateIssued());
            g.setTeacher(r.teacher());
            gradeRepository.save(g);
            saved++;
        }

        log.info("Grades — saved: {}, duplicates skipped: {}", saved, skipped);
    }

    @Transactional
    public void saveMessages(Student student, List<ScrapedData.MessageRecord> records) {
        int saved = 0, skipped = 0;

        for (ScrapedData.MessageRecord r : records) {
            if (messageRepository.existsByLibrusMessageId(r.librusMessageId())) {
                skipped++;
                continue;
            }

            Message m = new Message();
            m.setStudent(student);
            m.setLibrusMessageId(r.librusMessageId());
            m.setMessageType(r.messageType());
            m.setSender(findOrCreatePerson(r.messageSource().name(), r.sender(), r.senderRole()));
            m.setSubject(r.subject());
            m.setContent(r.content());
            m.setSentAt(r.sentAt());
            messageRepository.save(m);
            saved++;
        }

        log.info("Messages — saved: {}, duplicates skipped: {}", saved, skipped);
    }

    @Transactional
    public void linkStudentBcId(String bcId, String bcFullName) {
        if (bcId == null || bcId.isBlank() || bcFullName == null || bcFullName.isBlank()) return;

        if (studentRepository.findByBcId(bcId).isPresent()) {
            log.debug("BC id={} already linked — skipping", bcId);
            return;
        }

        String normalizedBcName = normalizeForSearch(bcFullName);
        studentRepository.findAll().stream()
                .filter(s -> normalizeForSearch(s.getFullName()).equals(normalizedBcName))
                .findFirst()
                .ifPresentOrElse(
                        s -> {
                            s.setBcId(bcId);
                            studentRepository.save(s);
                            log.info("Linked BC id={} to student '{}' (matched by name)", bcId, s.getFullName());
                        },
                        () -> log.warn("BC user '{}' (id={}) does not match any student by name — BC id not stored",
                                bcFullName, bcId)
                );
    }

    @Transactional
    public void saveAttendance(Student student, List<ScrapedData.AttendanceRecord> records) {
        int saved = 0, skipped = 0;

        for (ScrapedData.AttendanceRecord r : records) {
            boolean duplicate = attendanceRepository.existsByStudentAndDateAndLessonNumber(
                    student, r.date(), r.lessonNumber()
            );
            if (duplicate) {
                skipped++;
                continue;
            }

            Attendance a = new Attendance();
            a.setStudent(student);
            a.setDate(r.date());
            a.setLessonNumber(r.lessonNumber());
            a.setStatus(r.status());
            a.setSubject(r.subject());
            attendanceRepository.save(a);
            saved++;
        }

        log.info("Attendance — saved: {}, duplicates skipped: {}", saved, skipped);
    }

    @Transactional
    public void saveAnnouncements(Student student, List<ScrapedData.AnnouncementRecord> records) {
        int saved = 0, skipped = 0;

        for (ScrapedData.AnnouncementRecord r : records) {
            if (announcementRepository.existsByLibrusAnnouncementId(r.librusAnnouncementId())) {
                skipped++;
                continue;
            }

            Announcement a = new Announcement();
            a.setStudent(student);
            a.setLibrusAnnouncementId(r.librusAnnouncementId());
            a.setTitle(r.title());
            a.setContent(r.content());
            a.setAuthor(findOrCreatePerson(r.source(), r.author(), r.authorRole()));
            a.setPublishedAt(r.publishedAt());
            announcementRepository.save(a);
            saved++;
        }

        log.info("Announcements — saved: {}, duplicates skipped: {}", saved, skipped);
    }

    @Transactional
    public Person findOrCreatePerson(String sourceCode, String fullName, String role) {
        String safeName = fullName != null ? fullName.trim() : "";
        String safeRole = role     != null ? role.trim()     : "";

        Source source = sourceCache.computeIfAbsent(sourceCode, code ->
                sourceRepository.findByCode(code)
                        .orElseThrow(() -> new IllegalStateException("Unknown source code: " + code))
        );

        return personRepository.findBySourceAndFullNameAndRole(source, safeName, safeRole)
                .orElseGet(() -> personRepository.save(new Person(source, safeName, safeRole)));
    }

    // Strips Polish (and other) diacritics then lowercases, so "Świniarska" == "Swiniarska"
    private static String normalizeForSearch(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .trim();
    }
}
