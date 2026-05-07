package com.ecrharv.harvester.service;

import com.ecrharv.harvester.entity.Announcement;
import com.ecrharv.harvester.entity.Attendance;
import com.ecrharv.harvester.entity.Grade;
import com.ecrharv.harvester.entity.Message;
import com.ecrharv.harvester.entity.Person;
import com.ecrharv.harvester.entity.Source;
import com.ecrharv.harvester.entity.Student;
import com.ecrharv.harvester.enums.AttendanceStatus;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.repository.AnnouncementRepository;
import com.ecrharv.harvester.repository.AttendanceRepository;
import com.ecrharv.harvester.repository.GradeRepository;
import com.ecrharv.harvester.repository.MessageRepository;
import com.ecrharv.harvester.repository.PersonRepository;
import com.ecrharv.harvester.repository.SourceRepository;
import com.ecrharv.harvester.repository.StudentRepository;
import com.ecrharv.harvester.scraping.ScrapedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataPersistenceServiceTest {

    @Mock private StudentRepository      studentRepository;
    @Mock private GradeRepository        gradeRepository;
    @Mock private MessageRepository      messageRepository;
    @Mock private AttendanceRepository   attendanceRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private SourceRepository       sourceRepository;
    @Mock private PersonRepository       personRepository;

    @InjectMocks private DataPersistenceService service;

    private Student student;
    private Source  mockSource;
    private Person  mockPerson;

    @BeforeEach
    void setUp() {
        student = new Student();
        student.setLibrusUsername("testuser");
        student.setFullName("Test User");

        mockSource = mock(Source.class);
        lenient().when(mockSource.getCode()).thenReturn("LIBRUS");
        lenient().when(sourceRepository.findByCode(any())).thenReturn(Optional.of(mockSource));

        mockPerson = mock(Person.class);
        lenient().when(mockPerson.getFullName()).thenReturn("Teacher");
        lenient().when(personRepository.findBySourceAndFullNameAndRole(any(), any(), any()))
                 .thenReturn(Optional.of(mockPerson));
    }

    // ── findOrCreateStudent ───────────────────────────────────────────────────

    @Test
    void findOrCreateStudent_updatesAndSavesExistingStudent() {
        when(studentRepository.findByLibrusUsername("testuser")).thenReturn(Optional.of(student));
        when(studentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Student result = service.findOrCreateStudent("testuser", "Test User", "2a SP373");

        assertThat(result).isSameAs(student);
        assertThat(result.getClassName()).isEqualTo("2a SP373");
        verify(studentRepository).save(student);
    }

    @Test
    void findOrCreateStudent_createsAndSavesNewStudent() {
        when(studentRepository.findByLibrusUsername("newuser")).thenReturn(Optional.empty());
        when(studentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Student result = service.findOrCreateStudent("newuser", "New User", "3b SP373");

        assertThat(result.getLibrusUsername()).isEqualTo("newuser");
        assertThat(result.getFullName()).isEqualTo("New User");
        assertThat(result.getClassName()).isEqualTo("3b SP373");
        verify(studentRepository).save(any(Student.class));
    }

    // ── saveGrades ────────────────────────────────────────────────────────────

    @Test
    void saveGrades_skipsDuplicate() {
        var record = gradeRecord("Math", "5");
        when(gradeRepository.existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
                student, "Math", "5", record.dateIssued())).thenReturn(true);

        service.saveGrades(student, List.of(record));

        verify(gradeRepository, never()).save(any());
    }

    @Test
    void saveGrades_savesNewGrade() {
        var record = gradeRecord("Math", "5");
        when(gradeRepository.existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
                any(), any(), any(), any())).thenReturn(false);

        service.saveGrades(student, List.of(record));

        ArgumentCaptor<Grade> captor = ArgumentCaptor.forClass(Grade.class);
        verify(gradeRepository).save(captor.capture());
        Grade saved = captor.getValue();
        assertThat(saved.getSubjectName()).isEqualTo("Math");
        assertThat(saved.getGradeValue()).isEqualTo("5");
        assertThat(saved.getStudent()).isSameAs(student);
    }

    @Test
    void saveGrades_savesOnlyNonDuplicates() {
        var dup    = gradeRecord("Math", "5");
        var fresh  = gradeRecord("Physics", "4+");

        when(gradeRepository.existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
                student, "Math", "5", dup.dateIssued())).thenReturn(true);
        when(gradeRepository.existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
                student, "Physics", "4+", fresh.dateIssued())).thenReturn(false);

        service.saveGrades(student, List.of(dup, fresh));

        verify(gradeRepository, times(1)).save(any(Grade.class));
    }

    @Test
    void saveGrades_handlesEmptyList() {
        service.saveGrades(student, List.of());
        verifyNoInteractions(gradeRepository);
    }

    // ── saveMessages ──────────────────────────────────────────────────────────

    @Test
    void saveMessages_skipsDuplicateByLibrusId() {
        var record = messageRecord("msg-42");
        when(messageRepository.existsByLibrusMessageId("msg-42")).thenReturn(true);

        service.saveMessages(student, List.of(record));

        verify(messageRepository, never()).save(any());
    }

    @Test
    void saveMessages_savesNewMessage() {
        var record = messageRecord("msg-99");
        when(messageRepository.existsByLibrusMessageId("msg-99")).thenReturn(false);

        service.saveMessages(student, List.of(record));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        Message saved = captor.getValue();
        assertThat(saved.getLibrusMessageId()).isEqualTo("msg-99");
        assertThat(saved.getSender()).isSameAs(mockPerson);
        assertThat(saved.getSender().getFullName()).isEqualTo("Teacher");
        assertThat(saved.getStudent()).isSameAs(student);
    }

    @Test
    void saveMessages_handlesEmptyList() {
        service.saveMessages(student, List.of());
        verifyNoInteractions(messageRepository);
    }

    // ── saveAttendance ────────────────────────────────────────────────────────

    @Test
    void saveAttendance_skipsDuplicateSlot() {
        var record = attendanceRecord(1, AttendanceStatus.ABSENT);
        when(attendanceRepository.existsByStudentAndDateAndLessonNumber(
                student, record.date(), 1)).thenReturn(true);

        service.saveAttendance(student, List.of(record));

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void saveAttendance_savesNewRecord() {
        var record = attendanceRecord(2, AttendanceStatus.LATE);
        when(attendanceRepository.existsByStudentAndDateAndLessonNumber(
                any(), any(), anyInt())).thenReturn(false);

        service.saveAttendance(student, List.of(record));

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        Attendance saved = captor.getValue();
        assertThat(saved.getLessonNumber()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(AttendanceStatus.LATE);
        assertThat(saved.getStudent()).isSameAs(student);
    }

    @Test
    void saveAttendance_handlesEmptyList() {
        service.saveAttendance(student, List.of());
        verifyNoInteractions(attendanceRepository);
    }

    // ── saveAnnouncements ─────────────────────────────────────────────────────

    @Test
    void saveAnnouncements_skipsDuplicate() {
        var record = announcementRecord("ann-1");
        when(announcementRepository.existsByLibrusAnnouncementId("ann-1")).thenReturn(true);

        service.saveAnnouncements(student, List.of(record));

        verify(announcementRepository, never()).save(any());
    }

    @Test
    void saveAnnouncements_savesNewAnnouncement() {
        var record = announcementRecord("ann-2");
        when(announcementRepository.existsByLibrusAnnouncementId("ann-2")).thenReturn(false);

        service.saveAnnouncements(student, List.of(record));

        ArgumentCaptor<Announcement> captor = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepository).save(captor.capture());
        Announcement saved = captor.getValue();
        assertThat(saved.getLibrusAnnouncementId()).isEqualTo("ann-2");
        assertThat(saved.getTitle()).isEqualTo("Test announcement");
        assertThat(saved.getStudent()).isSameAs(student);
    }

    @Test
    void saveAnnouncements_handlesEmptyList() {
        service.saveAnnouncements(student, List.of());
        verifyNoInteractions(announcementRepository);
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private ScrapedData.GradeRecord gradeRecord(String subject, String value) {
        return new ScrapedData.GradeRecord(subject, "Test", value, 2, LocalDate.of(2024, 3, 15), "Teacher");
    }

    private ScrapedData.MessageRecord messageRecord(String id) {
        return new ScrapedData.MessageRecord(id, MessageType.INBOX, MessageSource.LIBRUS, "Teacher", "", "Subject", "Content", LocalDateTime.now());
    }

    private ScrapedData.AttendanceRecord attendanceRecord(int lesson, AttendanceStatus status) {
        return new ScrapedData.AttendanceRecord(LocalDate.of(2024, 3, 15), lesson, status, "Math");
    }

    private ScrapedData.AnnouncementRecord announcementRecord(String id) {
        return new ScrapedData.AnnouncementRecord(id, "LIBRUS", "Test announcement", "Content", "Teacher", "Nauczyciel", LocalDate.of(2024, 3, 15));
    }
}
