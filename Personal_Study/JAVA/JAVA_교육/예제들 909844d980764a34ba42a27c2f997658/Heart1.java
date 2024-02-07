package com.greedy.section04.practice1;

public class Heart1 implements Runnable{

	@Override
	public void run() {
		try {
			for(int i =0; i < 20; i++) {
			
				/*  매번 반복될 때마다 30줄 개행해서 콘솔에 이동하는 것처럼 보이게 하기 */
				for(int k = 0; k < 30; k++) {
					System.out.println();
				}
			/* 하트 출력 */
				if(i==0) {						// Heart가 포함된 쓰레드의 처음일 때(하트 하나만 출력)
					System.out.println("♡");
					System.out.println();
				} else {
					for(int j = 0; j < i; j++) {
						System.out.print(" ");
					}
					System.out.println("♡");
					
					/* 스타 출력 */
					for(int j = 0; j < i-1; j++) {		// j-1 은 하트보다 한 칸 뒤쳐지게
						System.out.print(" ");
					}
					System.out.println("☆");
				} 
				Thread.sleep(1000);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			
		}
	}

}
