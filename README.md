# Concurrent CSV Data Processor
### Employee Salary Processing System

A Spring Boot application demonstrating enterprise-grade concurrent data processing. The system reads employee records from a CSV file and calculates updated salaries in parallel using Java's Executor framework, with full thread-safety enforced through locks, semaphores, and atomic variables.

---

## Table of Contents

- [Technology Stack](#technology-stack)
- [Project Setup](#project-setup)
- [Project Structure](#project-structure)
- [CSV File Format](#csv-file-format)
- [Code Documentation](#code-documentation)
- [Concurrency Design](#concurrency-design)
- [Salary Calculation Logic](#salary-calculation-logic)

---

## Technology Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 17+ | Primary language |
| Spring Boot | 3.x | Application framework |
| Executor Framework | Built-in | Thread pool management |
| ReentrantLock | java.util.concurrent.locks | Fine-grained locking |
| Semaphore | java.util.concurrent | Concurrency throttling |
| AtomicInteger | java.util.concurrent.atomic | Lock-free counter |
| CopyOnWriteArrayList | java.util.concurrent | Thread-safe list |

---

## Project Setup

### Prerequisites

- Java Development Kit (JDK) 17 or higher
- Apache Maven 3.8+ or Gradle 7+
- An IDE such as IntelliJ IDEA or Eclipse
- Git

### Clone and Build

```bash
git clone https://github.com/your-org/concurrency-service.git
cd concurrency-service
```

**Maven:**
```bash
mvn clean install
```

**Gradle:**
```bash
./gradlew build
```

---

## Project Structure

```
src/
├── main/
   ├── java/
   │   └── com/ga/concurrency/
   │       ├── controller/
   │       │   └── ConcurrencyController.java  # REST controller — exposes salary processing via HTTP
   │       ├── service/
   │       │   └── ConcurrencyService.java     # Core service — CSV reading & thread pool processing
   │       ├── model/
   │       │   └── Employee.java               # Employee data model with salary fields
   │       └── enums/
   │           └── Role.java                   # Enum: Director, Manager, Employee
   └── resources/
       └── files/
           └── test_employees.csv              # Input data file

```

---

## CSV File Format

Place the employee data file at `src/main/resources/files/employees.csv`. No header row — columns must follow this exact order:

| Index | Field | Type | Example |
|---|---|---|---|
| 0 | id | int | `1` |
| 1 | name | String | `Alice Johnson` |
| 2 | salary | double | `75000.00` |
| 3 | joiningDate | LocalDate (ISO) | `2019-06-15` |
| 4 | role | Role enum | `Manager` |
| 5 | projectCompletion | double (0.0–1.0) | `0.85` |

**Example row:**
```
1,Alice Johnson,75000.00,2019-06-15,Manager,0.85
```

---

## Code Documentation

### `ConcurrencyService.java`

The central `@Service` class responsible for CSV ingestion and concurrent salary processing.

#### Class-Level Fields

| Field | Type | Purpose |
|---|---|---|
| `processedCount` | `AtomicInteger` | Tracks processed employees across threads using hardware-level CAS — no lock needed |
| `salaryLock` | `ReentrantLock` | Ensures only one thread mutates an employee's salary fields at a time |
| `semaphore` | `Semaphore(2)` | Limits concurrent salary calculations to 2 threads, even with a pool of 4 |
 
---

#### `readEmployeesFromCSV(String filePath)`

Reads a CSV file line-by-line and parses each row into an `Employee` object.

| Aspect | Detail |
|---|---|
| Parameter | `filePath` — absolute or relative path to the CSV file |
| Returns | `List<Employee>` — a `CopyOnWriteArrayList` of parsed employees |
| Exception | Throws `RuntimeException` wrapping any `IOException` |
| Thread Safety | `CopyOnWriteArrayList` ensures concurrent-write safety |
 
---

#### `incrementSalary(Employee emp)` *(private)*

Calculates and applies salary bonuses for a given employee. Always called from a thread pool task and protected by `salaryLock` to guarantee atomicity of the multi-step calculation.

**Concurrency controls within this method:**
- `salaryLock.lock()` is called before any field mutation
- `salaryLock.unlock()` is always called in a `finally` block
- `processedCount.incrementAndGet()` is atomic — no additional locking needed

---

#### `processEmployeesWithThreadPool(List<Employee>)`

Creates a fixed thread pool of 4 threads and submits one task per employee.

| Step | Action | Mechanism |
|---|---|---|
| 1 | Create thread pool | `Executors.newFixedThreadPool(4)` |
| 2 | Submit per-employee task | `fixedThreadPool.submit(Runnable)` |
| 3 | Acquire permit inside task | `semaphore.acquire()` |
| 4 | Lock and update salary | `salaryLock.lock()` / `unlock()` |
| 5 | Increment counter | `processedCount.incrementAndGet()` |
| 6 | Release permit | `semaphore.release()` in `finally` |
| 7 | Shutdown and await | `shutdown()` + `awaitTermination(1, MINUTES)` |
| 8 | Force shutdown on timeout | `shutdownNow()` |
 
---

### `Employee.java`

Represents a single employee record. All salary-related setter calls are serialized by `salaryLock` in `ConcurrencyService`, so no synchronization is needed within this class.

| Field | Type | Description |
|---|---|---|
| `id` | `int` | Unique employee identifier |
| `name` | `String` | Full name |
| `salary` | `double` | Base salary before bonuses |
| `joiningDate` | `LocalDate` | Date of joining — used to compute years of service |
| `role` | `Role` | Enum: `Director`, `Manager`, or `Employee` |
| `projectCompletion` | `double` | Completion rate (0.0–1.0); must be >= 0.6 to receive a bonus |
| `roleBonus` | `double` | Calculated role-based bonus (set during processing) |
| `yearBonus` | `double` | Calculated tenure-based bonus (set during processing) |
| `updatedSalary` | `double` | Final salary after all bonuses (set during processing) |
 
---

### `Role.java`

Simple enum driving the role bonus percentage:

| Value | Role Bonus |
|---|---|
| `Director` | 5% |
| `Manager` | 2% |
| `Employee` | 1% |
 
---

### `ConcurrencyController.java`

A `@RestController` that exposes the salary processing pipeline over HTTP. It is mapped to the base path `/api/concurrency` and delegates all business logic to `ConcurrencyService`.

#### Endpoints

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/api/concurrency/increment` | Reads the CSV, processes all employee salaries concurrently, and returns the updated records | `List<Employee>` (JSON) |

#### `GET /api/concurrency/increment`

Orchestrates the full processing pipeline in three steps:

1. Calls `readEmployeesFromCSV()` to load employee records from the configured CSV path
2. Calls `processEmployeesWithThreadPool()` to apply concurrent salary calculations
3. Maps each processed `Employee` into a new instance (preserving all fields including `roleBonus`, `yearBonus`, and `updatedSalary`) and returns the list as JSON

**Example request:**
```bash
curl http://localhost:8080/api/concurrency/increment
```

**Example response:**
```json
[
  {
    "id": 1,
    "name": "Alice Johnson",
    "salary": 75000.00,
    "joiningDate": "2019-06-15",
    "role": "Manager",
    "projectCompletion": 0.85,
    "roleBonus": 1500.00,
    "yearBonus": 9000.00,
    "updatedSalary": 85500.00
  }
]
```
 
---

## API Endpoints

| Method | URL | Description |
|---|---|---|
| `GET` | `/api/concurrency/increment` | Process all employee salaries and return updated records |
 
---

## Concurrency Design

### Mechanism Overview

| Mechanism | API | Where Used | Why |
|---|---|---|---|
| Fixed Thread Pool | `Executors.newFixedThreadPool(4)` | `processEmployeesWithThreadPool()` | Bounded resource usage; reuses threads instead of creating one per employee |
| Semaphore | `Semaphore(2)` | Inside each submitted task | Limits concurrent salary calculations to 2, reducing contention even with 4 threads |
| ReentrantLock | `ReentrantLock` | `incrementSalary()` | Guarantees the multi-step salary mutation (3 fields) is atomic — no thread observes a partial update |
| Atomic Variable | `AtomicInteger` | `processedCount` | Lock-free, thread-safe counter using CPU-level CAS — faster than a synchronized int |
| Thread-Safe Collection | `CopyOnWriteArrayList` | `readEmployeesFromCSV()` | Prevents data corruption if the list is accessed concurrently |

### Thread Execution Flow

```
Main Thread
  └── processEmployeesWithThreadPool()
        ├── Task 1 ──► Thread-1: semaphore.acquire() ──► lock() ──► incrementSalary() ──► unlock() ──► semaphore.release()
        ├── Task 2 ──► Thread-2: semaphore.acquire() ──► lock() ──► incrementSalary() ──► unlock() ──► semaphore.release()
        ├── Task 3 ──► Thread-3: semaphore.acquire() [BLOCKS — only 2 permits available] ──► waits...
        └── Task 4 ──► Thread-4: semaphore.acquire() [BLOCKS — only 2 permits available] ──► waits...
```

At most 2 threads execute `incrementSalary()` simultaneously. When one releases its permit, a waiting thread immediately acquires it and proceeds.

### Deadlock Prevention

There is only one lock (`salaryLock`) and one semaphore in the application. Since no code ever acquires two locks simultaneously, circular wait — the necessary condition for deadlock — cannot occur. Both `semaphore.release()` and `salaryLock.unlock()` are always called in `finally` blocks, guaranteeing they are freed even when exceptions occur.

---

## Salary Calculation Logic

| Bonus Type | Condition | Formula |
|---|---|---|
| Role Bonus | `role == Director` | `salary * 5%` |
| Role Bonus | `role == Manager` | `salary * 2%` |
| Role Bonus | All other roles | `salary * 1%` |
| Tenure Bonus | `yearsWorked >= 1` | `salary * (2% * yearsWorked)` |
| Final Salary | `projectCompletion >= 0.6` | `salary + roleBonus + yearBonus` |
| Final Salary | `projectCompletion < 0.6` | `salary` (no bonus applied) |
