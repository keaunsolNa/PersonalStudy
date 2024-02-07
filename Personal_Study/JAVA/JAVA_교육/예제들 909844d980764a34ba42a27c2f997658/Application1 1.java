package com.greedy.section02.variable;

public class Application1 {
	public static void main(String[] args) {
		
		/* 변수의 사용 목적 */
		/*
		 * 1. 값에 의미를 부여하기 위한 목적(가독성, 유지보수성, 협업에 용이)
		 * 2. 한 번 저장해 둔 값을 재사용 하기 위한 목적(재사용성에 용이)
		 * 3. 상황에 따라 변하는 값을 저장하고 사용하기 위한 목적(유지보수성에 용이)
		 */
		
		/* 1. 값에 의미를 부여하기 위한 목적 */
		System.out.println("======== 값에 의미 부여 테스트 ========");
		System.out.println("보너스를 포함한 급여: " + (1000000 + 20000) + "원");
		
		/* 변수를 사용해 보자. */
		int salary = 1000000;
		int bonus = 20000;

		System.out.println("보너스를 포함한 급여: " + (salary + bonus) + "원");

		/* 2. 한 번 저장해 둔 값을 재사용하기 위한 목적 */
		System.out.println("======== 변수에 저장한 값 재사용 테스트 ========");

		/* 10명의 고객에게 100 포인트를 지급해 주는 내용을 출력하도록 작성해 보자. */
		System.out.println("1번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("2번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("3번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("4번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("5번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("6번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("7번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("8번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("9번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println("10번 고객에게 포인트를 100 포인트를 지급하였습니다.");
		System.out.println();
		
		int point = 200;
		System.out.println("1번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("2번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("3번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("4번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("5번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("6번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("7번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("8번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("9번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		System.out.println("10번 고객에게 포인트를 " + point + " 포인트를 지급하였습니다.");
		
		/*
		 * 만약 포인트가 200포인트로 변경됐다면, 위의 코드는 10번의 실수 없는 수정 작업을 거쳐야 한다.
		 * 그러나 이게 1000줄의 코드에 있어서 그 모든 포인트를 수정해야 한다면 1000번을 실수 없이 수정해야 하는 것이다.
		 * 따라서 우리는 변수를 써서 같은 값을 실수 없이 활용하고, 수정할 수 있다.
		 */
		
		/* 3. 상황에 따라 변경되는 값을 저장하고 사용할 수 있다. */
		System.out.println("======== 변수에 저장된 값 변경 테스트 ========");
		
		/* 변수는 하나의 값을 변화하지 않고 활용하는 공간이기 보다는, 들어있는 값을 변화시키는 용도로 사용될 때가 많다. */
		
		int sum = 0;
//		int sum;
//		sum = 0;
		
		sum = sum + 10;		// sum <- 10
		System.out.println("sum에 10을 더하면 현재 sum의 값은? " + sum);
		
		sum = sum + 10;		// sum <- 20
		System.out.println("sum에 10을 더하면 현재 sum의 값은? " + sum);
		
		sum = sum + 10;		// sum <- 30
		System.out.println("sum에 10을 더하면 현재 sum의 값은? " + sum);
	}
}
