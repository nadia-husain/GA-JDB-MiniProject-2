package com.ga.concurrency.controller;

import com.ga.concurrency.model.Employee;
import com.ga.concurrency.service.ConcurrencyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/concurrency")
public class ConcurrencyController {
    private final ConcurrencyService concurrencyService;

    public ConcurrencyController(ConcurrencyService concurrencyService) {
        this.concurrencyService = concurrencyService;
    }

    @GetMapping("/increment")
    public List<Employee> getProcessedSalaries() {

        // read employees
        List<Employee> employees = concurrencyService.readEmployeesFromCSV("/Users/nadiahusain/jdb/projects/concurrency/src/main/resources/files/test_employees.csv");

        // process employees
        concurrencyService.processEmployeesWithThreadPool(employees);

        // return updated salary
        return employees.stream()
                .map(emp -> new Employee(
                        emp.getId(),
                        emp.getName(),
                        emp.getSalary(),
                        emp.getJoiningDate(),
                        emp.getRole(),
                        emp.getProjectCompletion(),
                        emp.getUpdatedSalary())
                )
                .collect(Collectors.toList());

    }

}
