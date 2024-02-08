# PC(Program Counter)

# Definition

- PC는 현재 실행 중인 명령(Instruction)의 주소(위치)를 포함하는 컴퓨터 프로세서의 레지스터이다.
- 각 명령을 가져올 때 PC는 저장된 값을 1씩 증가시킨다. 각 명령을 가져온 후 PC는 시퀸스의 다음 명령을 가리키며, 컴퓨터가 다시 시작되거나 재설정되면 PC는 일반적으로 0으로 돌아간다.
- Intel x86, Itanium microprocessors에서 IP(Instruction Pointer)라고 하며, 때로는 IAR(Instruction Address Register)라고 한다.
- 프로세서는 일반적으로 메모리에서 순차적으로 명령을 가져오지만 제어 전송 명령(control transfer instructions)은 PC에 새 값을 배치하여 시퀀스를 변경한다. 여기에는 branches(jumps라고도 한다), subroutine 호출 및 반환이 포함된다.
- 일부 assertion의 참에 대한 조건부 전송을 통해 컴퓨터는 다른 조건에서 다른 시퀀스를 따를 수 있다.
    - assertion : 프로그램의 한 지점에 연결된 술어(상태 공간에 대한 boolean 값 함수, 일반적으로 프로그램의 변수를 사용하여 논리적 명제로 표현됨)
- branch는 메모리의 다른 곳에서 다음 명령을 가져오는 것을 제공한다. subroutine 호출을 분기하며, PC의 이전 내용을 저장한다. 리턴은 PC의 저장된 내용을 검색하여 PC에 다시 배치하고 subroutine 호출 다음에 오는 명령으로 순차적 실행을 재개한다.
- PC는 각각 PC 값의 1비트를 나타내는 bank of binary latches일 수 있다.

# Instruction Cycle

- 명령 주기(Instruction cycle)는 CPU가 PC의 값을 Data bus에 배치하여 메모리로 보내는 fetch로 시작한다.
- 메모리는 Data bus에서 해당 메모리 위치의 내용을 전송하여 응답한다.
- fetch 이후 CPU는 실행을 진행하여 얻은 메모리 내용에 따라 몇 가지 작업을 수행한다. 이 주기의 특정 지점에서 PC는 다음에 실행되는 명령이 다른 명령이 되도록 수정된다.
    - 일반적으로 다음 명령이 현재 명령의 마지막 메모리 위치 바로 다음에 오는 메모리 주소에서 시작하는 명령이 되도록 증분(incremented)됨.
- CPU는 다음에 실행할 명령어가 저장되어 있는 주소를 PC에서 읽어서 순차적으로 실행한다.
    - 실행 명령 호출
        
        ![Untitled](PC(Program_Counter)/Untitled.png)
        
    - 다음 명령 주소 호출(Program Counter)
    
    ![Untitled](PC(Program_Counter)/Untitled%201.png)
    

# Accumulator

- 누산기는 CPU에 포함된 일종의 레지스터로, 수학 및 논리적 계산에서 중간 값을 보유하는 임시 저장 위치 역할을 한다.
- 작업의 중간 결과는 누산기에 점진적으로 기록되어 이전 값을 덮어쓴다.
- 최신 컴퓨터 시스템에는 모든 레지스터가 누산기 역할을 할 수 있다.
    
    ![Untitled](PC(Program_Counter)/Untitled%202.png)