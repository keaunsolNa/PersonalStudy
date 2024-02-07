package com.greedy.section03.array_copy;

import java.util.Arrays;

public class Application2 {
	public static void main(String [] args) {
		
		/*
		 *  깊은 복사는 heap에 생성된 배열이 가지고 있는 값을
		 *  또 다른 배열에 복사 해 놓는 것이다.
		 *  서로 같은 값을 가지고 있지만, 두 배열은 서로 다른 배열이기에
		 *  하나의 배열을 변경 하더라고 다른 배열에는 영향을 주지 않는다.
		 */
		
		/*
		 * 깊은 복사를 하는 방법은 4가지가 있다.
		 * 1. for문과 인덱스를 통한 값 복사
		 * 2. object의 clone()을 이용한 복사
		 * 3. System의 arraycopy()를 이용한 복사
		 * 4. Arrays의 copyOf()를 이용한 복사
		 * 
		 * 2번은 그대로 값 복사를 할 때 자주 사용되고 3번과 4번은 복사하는 배열을
		 * 원하는 길이만큼 조절할 수 있다는 특징을 가지고 있다.
		 */
		
		/* 원본 배열 할당 및 초기화 */
		int[] originArr = new int[] {1, 2, 3, 4, 5};
		
		print(originArr);
		
		/* 1. 첫번째 방법 */
		
		int[] copyArr = new int[originArr.length];
		for(int i = 0; i < originArr.length; i++) {
			copyArr[i] = originArr[i];
		}
		
		print(copyArr);
		
		/* 
		 * 2. 두번째 방법. 
		 * Object의 clone()을 이용한 복사 
		 */
		int[] copyArr2 = originArr.clone();
		
		print(copyArr2);
		
		/* 3. 세번째 방법 */
		int[] copyArr3 = new int[10];
		
		System.arraycopy(originArr, 0, copyArr3, 1, 2);
		//originArr의 0번째 배열부터 2번째 칸까지를 복사하여 copyArr3에 1번째 칸부터 넣겠다.
		System.out.println("3");
		print(copyArr3);
		
		/* 4. 4번째 방법 */
		int[] copyArr4 = Arrays.copyOf(originArr, 3);
		//originArr의 배열을 3칸만큼을 복사하여, 복사한 만큼의 copyArr4를 생성한다.
		System.out.println("4");
		print(copyArr4);
	}	//main_end

	public static void print(int[] iArr) {
		/* 전달받은 배열의 주소 출력 */
		System.out.println("전달받은 1차원 배열의 hashcode: " + iArr.hashCode());
		
		/* 전달받은 배열의 값 출력 */
		System.out.println(Arrays.toString(iArr));
	}	//print_end
		
		
}	//class_end
