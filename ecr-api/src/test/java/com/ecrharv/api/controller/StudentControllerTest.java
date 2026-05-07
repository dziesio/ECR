package com.ecrharv.api.controller;

import com.ecrharv.api.entity.Attendance;
import com.ecrharv.api.entity.Grade;
import com.ecrharv.api.entity.Message;
import com.ecrharv.api.entity.Student;
import com.ecrharv.api.enums.AttendanceStatus;
import com.ecrharv.api.repository.AttendanceRepository;
import com.ecrharv.api.repository.GradeRepository;
import com.ecrharv.api.repository.MessageRepository;
import com.ecrharv.api.repository.StudentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StudentController.class)
class StudentControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private StudentRepository    studentRepository;
    @MockBean private GradeRepository      gradeRepository;
    @MockBean private MessageRepository    messageRepository;
    @MockBean private AttendanceRepository attendanceRepository;

    private UUID studentId;
    private Student student;

    @BeforeEach
    void setUp() {
        studentId = UUID.randomUUID();
        student   = buildStudent(studentId, "jan.kowalski", "Jan Kowalski");
    }

    // ── GET /api/students ─────────────────────────────────────────────────────

    @Test
    void listStudents_returnsEmptyJsonArray() throws Exception {
        when(studentRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/students"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void listStudents_returnsAllStudents() throws Exception {
        when(studentRepository.findAll()).thenReturn(List.of(student));

        mockMvc.perform(get("/api/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].librusUsername", is("jan.kowalski")))
                .andExpect(jsonPath("$[0].fullName", is("Jan Kowalski")));
    }

    // ── GET /api/students/{id} ────────────────────────────────────────────────

    @Test
    void getStudent_returnsStudentWhenFound() throws Exception {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));

        mockMvc.perform(get("/api/students/{id}", studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(studentId.toString())))
                .andExpect(jsonPath("$.fullName", is("Jan Kowalski")));
    }

    @Test
    void getStudent_returns404WhenNotFound() throws Exception {
        when(studentRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/students/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/students/{id}/grades ─────────────────────────────────────────

    @Test
    void getGrades_returnsAllGrades() throws Exception {
        Grade grade = buildGrade("Matematyka", "5", 2);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(gradeRepository.findByStudent(student)).thenReturn(List.of(grade));

        mockMvc.perform(get("/api/students/{id}/grades", studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].subjectName", is("Matematyka")))
                .andExpect(jsonPath("$[0].gradeValue", is("5")))
                .andExpect(jsonPath("$[0].weight", is(2)));
    }

    @Test
    void getGrades_filtersBySubjectParam() throws Exception {
        Grade grade = buildGrade("Fizyka", "4+", 1);
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(gradeRepository.findByStudentAndSubjectName(student, "Fizyka")).thenReturn(List.of(grade));

        mockMvc.perform(get("/api/students/{id}/grades", studentId).param("subject", "Fizyka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].subjectName", is("Fizyka")));
    }

    @Test
    void getGrades_returns404WhenStudentNotFound() throws Exception {
        when(studentRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/students/{id}/grades", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getGrades_returnsEmptyListWhenNoGrades() throws Exception {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(gradeRepository.findByStudent(student)).thenReturn(List.of());

        mockMvc.perform(get("/api/students/{id}/grades", studentId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── GET /api/students/{id}/messages ──────────────────────────────────────

    @Test
    void getMessages_returnsMessagesOrderedByDate() throws Exception {
        Message m1 = buildMessage("Wychowawca", "Zebranie", LocalDateTime.of(2024, 3, 20, 10, 0));
        Message m2 = buildMessage("Dyrektor",   "Ogłoszenie", LocalDateTime.of(2024, 3, 15, 9, 0));
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(messageRepository.findByStudentOrderBySentAtDesc(student)).thenReturn(List.of(m1, m2));

        mockMvc.perform(get("/api/students/{id}/messages", studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sender", is("Wychowawca")))
                .andExpect(jsonPath("$[1].sender", is("Dyrektor")));
    }

    @Test
    void getMessages_returns404WhenStudentNotFound() throws Exception {
        when(studentRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/students/{id}/messages", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/students/{id}/attendance ─────────────────────────────────────

    @Test
    void getAttendance_returnsAllRecords() throws Exception {
        Attendance rec = buildAttendance(LocalDate.of(2024, 3, 18), 3, AttendanceStatus.ABSENT, "Fizyka");
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(attendanceRepository.findByStudentOrderByDateDescLessonNumberAsc(student))
                .thenReturn(List.of(rec));

        mockMvc.perform(get("/api/students/{id}/attendance", studentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("ABSENT")))
                .andExpect(jsonPath("$[0].subject", is("Fizyka")))
                .andExpect(jsonPath("$[0].lessonNumber", is(3)));
    }

    @Test
    void getAttendance_filtersByStatusParam() throws Exception {
        Attendance rec = buildAttendance(LocalDate.of(2024, 3, 18), 1, AttendanceStatus.LATE, "Chemia");
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(attendanceRepository.findByStudentAndStatusOrderByDateDesc(student, AttendanceStatus.LATE))
                .thenReturn(List.of(rec));

        mockMvc.perform(get("/api/students/{id}/attendance", studentId).param("status", "LATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("LATE")));
    }

    @Test
    void getAttendance_returns404WhenStudentNotFound() throws Exception {
        when(studentRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/students/{id}/attendance", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAttendance_returnsEmptyListWhenNoRecords() throws Exception {
        when(studentRepository.findById(studentId)).thenReturn(Optional.of(student));
        when(attendanceRepository.findByStudentOrderByDateDescLessonNumberAsc(student))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/students/{id}/attendance", studentId))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    // ── Entity builders (ReflectionTestUtils since entities have no setters) ──

    private Student buildStudent(UUID id, String username, String fullName) {
        Student s = new Student();
        ReflectionTestUtils.setField(s, "id",             id);
        ReflectionTestUtils.setField(s, "librusUsername", username);
        ReflectionTestUtils.setField(s, "fullName",       fullName);
        ReflectionTestUtils.setField(s, "createdAt",      LocalDateTime.now());
        return s;
    }

    private Grade buildGrade(String subject, String value, int weight) {
        Grade g = new Grade();
        ReflectionTestUtils.setField(g, "id",          UUID.randomUUID());
        ReflectionTestUtils.setField(g, "student",     student);
        ReflectionTestUtils.setField(g, "subjectName", subject);
        ReflectionTestUtils.setField(g, "category",    "Sprawdzian");
        ReflectionTestUtils.setField(g, "gradeValue",  value);
        ReflectionTestUtils.setField(g, "weight",      weight);
        ReflectionTestUtils.setField(g, "dateIssued",  LocalDate.of(2024, 3, 15));
        ReflectionTestUtils.setField(g, "teacher",     "Jan Nowak");
        return g;
    }

    private Message buildMessage(String sender, String subject, LocalDateTime sentAt) {
        Message m = new Message();
        ReflectionTestUtils.setField(m, "id",              UUID.randomUUID());
        ReflectionTestUtils.setField(m, "student",         student);
        ReflectionTestUtils.setField(m, "librusMessageId", UUID.randomUUID().toString());
        ReflectionTestUtils.setField(m, "sender",          sender);
        ReflectionTestUtils.setField(m, "subject",         subject);
        ReflectionTestUtils.setField(m, "content",         "Treść wiadomości");
        ReflectionTestUtils.setField(m, "sentAt",          sentAt);
        return m;
    }

    private Attendance buildAttendance(LocalDate date, int lesson, AttendanceStatus status, String subject) {
        Attendance a = new Attendance();
        ReflectionTestUtils.setField(a, "id",           UUID.randomUUID());
        ReflectionTestUtils.setField(a, "student",      student);
        ReflectionTestUtils.setField(a, "date",         date);
        ReflectionTestUtils.setField(a, "lessonNumber", lesson);
        ReflectionTestUtils.setField(a, "status",       status);
        ReflectionTestUtils.setField(a, "subject",      subject);
        return a;
    }
}
