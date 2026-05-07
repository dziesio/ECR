package com.ecrharv.api.controller;

import com.ecrharv.api.dto.*;
import com.ecrharv.api.entity.Student;
import com.ecrharv.api.enums.AttendanceStatus;
import com.ecrharv.api.repository.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/students")
@RequiredArgsConstructor
@Tag(name = "Students", description = "Access students and their harvested Librus data")
public class StudentController {

    private final StudentRepository      studentRepository;
    private final GradeRepository        gradeRepository;
    private final MessageRepository      messageRepository;
    private final AttendanceRepository   attendanceRepository;
    private final AnnouncementRepository announcementRepository;

    // ── Students ─────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all students")
    public List<StudentResponse> listStudents() {
        return studentRepository.findAll().stream()
                .map(StudentResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a student by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Student found"),
        @ApiResponse(responseCode = "404", description = "Student not found")
    })
    public ResponseEntity<StudentResponse> getStudent(
            @Parameter(description = "Student UUID") @PathVariable UUID id
    ) {
        return studentRepository.findById(id)
                .map(StudentResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Grades ────────────────────────────────────────────────────────────────

    @GetMapping("/{id}/grades")
    @Operation(summary = "Get grades", description = "Returns all grades for the student, optionally filtered by subject name")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Grades returned"),
        @ApiResponse(responseCode = "404", description = "Student not found")
    })
    public ResponseEntity<List<GradeResponse>> getGrades(
            @Parameter(description = "Student UUID") @PathVariable UUID id,
            @Parameter(description = "Filter by subject name, e.g. Matematyka") @RequestParam(required = false) String subject
    ) {
        return studentRepository.findById(id)
                .map(student -> {
                    List<GradeResponse> grades = (subject != null)
                            ? gradeRepository.findByStudentAndSubjectName(student, subject).stream()
                                    .map(GradeResponse::from).toList()
                            : gradeRepository.findByStudent(student).stream()
                                    .map(GradeResponse::from).toList();
                    return ResponseEntity.ok(grades);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/messages")
    @Operation(summary = "Get messages", description = "Returns inbox messages ordered by date descending")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Messages returned"),
        @ApiResponse(responseCode = "404", description = "Student not found")
    })
    public ResponseEntity<List<MessageResponse>> getMessages(
            @Parameter(description = "Student UUID") @PathVariable UUID id
    ) {
        return studentRepository.findById(id)
                .map(student -> ResponseEntity.ok(
                        messageRepository.findByStudentOrderBySentAtDesc(student).stream()
                                .map(MessageResponse::from)
                                .toList()
                ))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Announcements ─────────────────────────────────────────────────────────

    @GetMapping("/{id}/announcements")
    @Operation(summary = "Get announcements", description = "Returns announcements ordered by date descending")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Announcements returned"),
        @ApiResponse(responseCode = "404", description = "Student not found")
    })
    public ResponseEntity<List<AnnouncementResponse>> getAnnouncements(
            @Parameter(description = "Student UUID") @PathVariable UUID id
    ) {
        return studentRepository.findById(id)
                .map(student -> ResponseEntity.ok(
                        announcementRepository.findByStudentOrderByPublishedAtDesc(student).stream()
                                .map(AnnouncementResponse::from)
                                .toList()
                ))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Attendance ────────────────────────────────────────────────────────────

    @GetMapping("/{id}/attendance")
    @Operation(summary = "Get attendance", description = "Returns attendance records ordered by date descending, optionally filtered by status")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Attendance records returned"),
        @ApiResponse(responseCode = "404", description = "Student not found")
    })
    public ResponseEntity<List<AttendanceResponse>> getAttendance(
            @Parameter(description = "Student UUID") @PathVariable UUID id,
            @Parameter(description = "Filter by status: PRESENT, ABSENT, LATE, EXCUSED") @RequestParam(required = false) AttendanceStatus status
    ) {
        return studentRepository.findById(id)
                .map(student -> {
                    List<AttendanceResponse> records = (status != null)
                            ? attendanceRepository.findByStudentAndStatusOrderByDateDesc(student, status).stream()
                                    .map(AttendanceResponse::from).toList()
                            : attendanceRepository.findByStudentOrderByDateDescLessonNumberAsc(student).stream()
                                    .map(AttendanceResponse::from).toList();
                    return ResponseEntity.ok(records);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
